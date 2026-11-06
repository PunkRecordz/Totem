use std::sync::atomic::{AtomicI64, Ordering};

use rayon::prelude::*;

use super::decode::{decode_varint_shorts, decode_varint_shorts_slice};
use super::encode::{encode_shorts_varint, encode_shorts_varint_slice};
use super::partition::{count_varint_terminals_slice, find_varint_boundaries_slice};
use super::size::{varint_encoded_size, varint_encoded_size_slice};

const PARALLEL_THRESHOLD_ELEMENTS: usize = 65_536;
const MAX_PARTITIONS: usize = 64;

fn decode_parallel_slices(
    source: &[u8],
    destination: &mut [u16],
    thread_count: usize,
) -> i64 {
    let expected_count = destination.len();
    let mut byte_boundaries = [0u64; MAX_PARTITIONS + 1];
    find_varint_boundaries_slice(source, thread_count, &mut byte_boundaries);

    let mut terminal_counts = [0usize; MAX_PARTITIONS];
    terminal_counts[..thread_count]
        .par_iter_mut()
        .enumerate()
        .for_each(|(partition_index, terminal_slot)| {
            let start_byte = byte_boundaries[partition_index] as usize;
            let end_byte = byte_boundaries[partition_index + 1] as usize;
            if end_byte > start_byte {
                *terminal_slot = count_varint_terminals_slice(&source[start_byte..end_byte]);
            }
        });

    let mut assigned_counts = [0usize; MAX_PARTITIONS];
    let mut total_decoded = 0usize;
    for partition_index in 0..thread_count {
        let remaining = expected_count.saturating_sub(total_decoded);
        let assigned = terminal_counts[partition_index].min(remaining);
        assigned_counts[partition_index] = assigned;
        total_decoded += assigned;
    }

    if total_decoded < expected_count {
        return -2;
    }

    let mut destination_parts: Vec<&mut [u16]> = Vec::with_capacity(thread_count);
    let mut remaining_destination = destination;
    for &assigned_count in &assigned_counts[..thread_count] {
        let (part, rest) = remaining_destination.split_at_mut(assigned_count);
        destination_parts.push(part);
        remaining_destination = rest;
    }

    let error_code = AtomicI64::new(0);
    let mut consumed_per_partition = [0u64; MAX_PARTITIONS];

    destination_parts
        .into_par_iter()
        .zip(&mut consumed_per_partition[..thread_count])
        .enumerate()
        .for_each(|(partition_index, (destination_part, consumed_slot))| {
            if destination_part.is_empty() || error_code.load(Ordering::Relaxed) < 0 {
                return;
            }
            let start_byte = byte_boundaries[partition_index] as usize;
            let end_byte = byte_boundaries[partition_index + 1] as usize;
            let status = decode_varint_shorts_slice(&source[start_byte..end_byte], destination_part);
            if status < 0 {
                let _ = error_code.compare_exchange(0, status, Ordering::Relaxed, Ordering::Relaxed);
            } else {
                *consumed_slot = status as u64;
            }
        });

    let recorded_error = error_code.load(Ordering::Relaxed);
    if recorded_error < 0 {
        return recorded_error;
    }

    let mut last_active = 0usize;
    for partition_index in 0..thread_count {
        if assigned_counts[partition_index] > 0 {
            last_active = partition_index;
        }
    }

    (byte_boundaries[last_active] + consumed_per_partition[last_active]) as i64
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn totem_decode_varint_shorts_parallel(
    source: *const u8,
    source_length: u64,
    destination: *mut u16,
    expected_length: u32,
) -> i64 {
    let expected_count = expected_length as usize;
    let byte_length = source_length as usize;

    if expected_count == 0 {
        return 0;
    }
    if source.is_null() || destination.is_null() {
        return -1;
    }

    let thread_count = rayon::current_num_threads().clamp(1, MAX_PARTITIONS);
    if expected_count < PARALLEL_THRESHOLD_ELEMENTS
        || byte_length < PARALLEL_THRESHOLD_ELEMENTS
        || thread_count <= 1
    {
        return unsafe { decode_varint_shorts(source, source_length, destination, expected_length) };
    }

    let source_slice = unsafe { core::slice::from_raw_parts(source, byte_length) };
    if !destination.is_aligned() {
        let mut aligned_destination = vec![0u16; expected_count];
        let status = decode_parallel_slices(source_slice, &mut aligned_destination, thread_count);
        if status >= 0 {
            unsafe {
                core::ptr::copy_nonoverlapping(
                    aligned_destination.as_ptr() as *const u8,
                    destination as *mut u8,
                    expected_count * core::mem::size_of::<u16>(),
                );
            }
        }
        return status;
    }

    let destination_slice = unsafe { core::slice::from_raw_parts_mut(destination, expected_count) };
    decode_parallel_slices(source_slice, destination_slice, thread_count)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn totem_varint_partition_sizes(
    source: *const u16,
    element_count: u64,
    out_partition_sizes: *mut u64,
    max_partitions: u32,
    out_partition_count: *mut u32,
    out_chunk_size: *mut u64,
) -> i64 {
    let count = element_count as usize;
    if count == 0 {
        if !out_partition_count.is_null() {
            unsafe { out_partition_count.write_unaligned(0) };
        }
        if !out_chunk_size.is_null() {
            unsafe { out_chunk_size.write_unaligned(0) };
        }
        return 0;
    }
    if source.is_null()
        || out_partition_sizes.is_null()
        || out_partition_count.is_null()
        || out_chunk_size.is_null()
        || max_partitions == 0
    {
        return -1;
    }

    let thread_count = rayon::current_num_threads()
        .clamp(1, MAX_PARTITIONS)
        .min(max_partitions as usize);

    if count < PARALLEL_THRESHOLD_ELEMENTS || thread_count <= 1 {
        let total_size = unsafe { varint_encoded_size(source, element_count) };
        unsafe {
            out_partition_sizes.write_unaligned(total_size);
            out_partition_count.write_unaligned(1);
            out_chunk_size.write_unaligned(element_count);
        }
        return total_size as i64;
    }

    let chunk_size = count.div_ceil(thread_count);
    let partition_count = count.div_ceil(chunk_size);
    let mut local_sizes = [0u64; MAX_PARTITIONS];

    if source.is_aligned() {
        let source_slice = unsafe { core::slice::from_raw_parts(source, count) };
        source_slice
            .par_chunks(chunk_size)
            .zip(&mut local_sizes[..partition_count])
            .for_each(|(part_slice, size_slot)| {
                *size_slot = varint_encoded_size_slice(part_slice);
            });
    } else {
        let mut aligned_source = vec![0u16; count];
        unsafe {
            core::ptr::copy_nonoverlapping(
                source as *const u8,
                aligned_source.as_mut_ptr() as *mut u8,
                count * core::mem::size_of::<u16>(),
            );
        }
        aligned_source
            .par_chunks(chunk_size)
            .zip(&mut local_sizes[..partition_count])
            .for_each(|(part_slice, size_slot)| {
                *size_slot = varint_encoded_size_slice(part_slice);
            });
    }

    let mut total_bytes = 0u64;
    for (partition_index, &partition_bytes) in local_sizes[..partition_count].iter().enumerate() {
        unsafe { out_partition_sizes.add(partition_index).write_unaligned(partition_bytes) };
        total_bytes = match total_bytes.checked_add(partition_bytes) {
            Some(sum) => sum,
            None => return -1,
        };
    }

    unsafe {
        out_partition_count.write_unaligned(partition_count as u32);
        out_chunk_size.write_unaligned(chunk_size as u64);
    }

    total_bytes as i64
}

fn encode_parallel_slices(
    source: &[u16],
    partition_sizes: &[usize],
    chunk_size: usize,
    destination: &mut [u8],
) {
    let mut destination_parts: Vec<&mut [u8]> = Vec::with_capacity(partition_sizes.len());
    let mut remaining_destination = destination;
    for &partition_bytes in partition_sizes {
        let (part, rest) = remaining_destination.split_at_mut(partition_bytes);
        destination_parts.push(part);
        remaining_destination = rest;
    }

    source
        .par_chunks(chunk_size)
        .zip(destination_parts)
        .for_each(|(source_part, destination_part)| {
            if !source_part.is_empty() && !destination_part.is_empty() {
                encode_shorts_varint_slice(source_part, destination_part);
            }
        });
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn totem_encode_shorts_varint_parallel(
    source: *const u16,
    element_count: u64,
    partition_sizes: *const u64,
    partition_count: u32,
    chunk_size: u64,
    destination: *mut u8,
    destination_length: u64,
) -> i64 {
    let count = element_count as usize;
    let partitions = partition_count as usize;
    let step = chunk_size as usize;
    if count == 0 || partitions == 0 {
        return 0;
    }
    if source.is_null()
        || partition_sizes.is_null()
        || destination.is_null()
        || step == 0
        || partitions > MAX_PARTITIONS
    {
        return -1;
    }

    if partitions == 1 {
        return unsafe { encode_shorts_varint(source, element_count, destination, destination_length) }
            as i64;
    }

    let mut sizes = [0usize; MAX_PARTITIONS];
    let mut total_bytes = 0usize;
    for partition_index in 0..partitions {
        let part_bytes = unsafe { partition_sizes.add(partition_index).read_unaligned() } as usize;
        sizes[partition_index] = part_bytes;
        total_bytes = match total_bytes.checked_add(part_bytes) {
            Some(sum) => sum,
            None => return -1,
        };
    }
    if total_bytes > destination_length as usize {
        return -1;
    }

    let destination_slice = unsafe { core::slice::from_raw_parts_mut(destination, total_bytes) };
    if source.is_aligned() {
        let source_slice = unsafe { core::slice::from_raw_parts(source, count) };
        encode_parallel_slices(source_slice, &sizes[..partitions], step, destination_slice);
    } else {
        let mut aligned_source = vec![0u16; count];
        unsafe {
            core::ptr::copy_nonoverlapping(
                source as *const u8,
                aligned_source.as_mut_ptr() as *mut u8,
                count * core::mem::size_of::<u16>(),
            );
        }
        encode_parallel_slices(&aligned_source, &sizes[..partitions], step, destination_slice);
    }

    total_bytes as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parallel_varint_roundtrip() {
        let sample: Vec<u16> = (0..150_005u32)
            .map(|index| match index % 3 {
                0 => (index % 128) as u16,
                1 => (128 + (index % 16_000)) as u16,
                _ => (20_000 + (index % 40_000)) as u16,
            })
            .collect();

        let mut sizes = [0u64; MAX_PARTITIONS];
        let mut part_count = 0u32;
        let mut chunk_size = 0u64;
        let total_bytes = unsafe {
            totem_varint_partition_sizes(
                sample.as_ptr(),
                sample.len() as u64,
                sizes.as_mut_ptr(),
                MAX_PARTITIONS as u32,
                &mut part_count,
                &mut chunk_size,
            )
        };
        assert!(total_bytes > 0);

        let mut encoded = vec![0u8; total_bytes as usize];
        let written = unsafe {
            totem_encode_shorts_varint_parallel(
                sample.as_ptr(),
                sample.len() as u64,
                sizes.as_ptr(),
                part_count,
                chunk_size,
                encoded.as_mut_ptr(),
                encoded.len() as u64,
            )
        };
        assert_eq!(written, total_bytes);

        let mut decoded = vec![0u16; sample.len()];
        let consumed = unsafe {
            totem_decode_varint_shorts_parallel(
                encoded.as_ptr(),
                encoded.len() as u64,
                decoded.as_mut_ptr(),
                sample.len() as u32,
            )
        };
        assert_eq!(consumed, total_bytes);
        assert_eq!(decoded, sample);
    }

    #[test]
    fn test_unaligned_parallel_varint_roundtrip() {
        let element_count = 80_000usize;
        let mut unaligned_source = vec![0u8; element_count * 2 + 1];
        for index in 0..element_count {
            let value = ((index * 13) % 50_000) as u16;
            unaligned_source[1 + index * 2..1 + index * 2 + 2].copy_from_slice(&value.to_le_bytes());
        }
        let unaligned_source_ptr = unsafe { unaligned_source.as_ptr().add(1) as *const u16 };

        let mut sizes = [0u64; MAX_PARTITIONS];
        let mut part_count = 0u32;
        let mut chunk_size = 0u64;
        let total_bytes = unsafe {
            totem_varint_partition_sizes(
                unaligned_source_ptr,
                element_count as u64,
                sizes.as_mut_ptr(),
                MAX_PARTITIONS as u32,
                &mut part_count,
                &mut chunk_size,
            )
        };
        assert!(total_bytes > 0);

        let mut encoded = vec![0u8; total_bytes as usize];
        let written = unsafe {
            totem_encode_shorts_varint_parallel(
                unaligned_source_ptr,
                element_count as u64,
                sizes.as_ptr(),
                part_count,
                chunk_size,
                encoded.as_mut_ptr(),
                encoded.len() as u64,
            )
        };
        assert_eq!(written, total_bytes);

        let mut unaligned_decoded = vec![0u8; element_count * 2 + 1];
        let unaligned_decoded_ptr = unsafe { unaligned_decoded.as_mut_ptr().add(1) as *mut u16 };
        let consumed = unsafe {
            totem_decode_varint_shorts_parallel(
                encoded.as_ptr(),
                encoded.len() as u64,
                unaligned_decoded_ptr,
                element_count as u32,
            )
        };
        assert_eq!(consumed, total_bytes);
        assert_eq!(
            &unaligned_decoded[1..1 + element_count * 2],
            &unaligned_source[1..1 + element_count * 2]
        );
    }
}

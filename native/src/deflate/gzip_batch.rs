use std::sync::atomic::{AtomicBool, Ordering};

use rayon::prelude::*;

use super::codec::{self, DeflateFormat};
use super::{STATUS_BAD_DATA, STATUS_INSUFFICIENT_SPACE, STATUS_INVALID_ARGUMENT};
use crate::ByteSliceDescriptor;

const PARALLEL_THRESHOLD_BYTES: usize = 256 * 1024;
const MIN_CHUNK_SIZE_BYTES: usize = 128 * 1024;
const MAX_CHUNK_SIZE_BYTES: usize = 4 * 1024 * 1024;

#[derive(Clone, Copy)]
struct PlannedChunk {
    address: usize,
    length: usize,
    staging_offset: usize,
    staging_bound: usize,
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn totem_gzip_slices_bound(
    descriptors: *const ByteSliceDescriptor,
    slice_count: u32,
) -> i64 {
    if slice_count == 0 {
        return 0;
    }
    if descriptors.is_null() || !descriptors.is_aligned() {
        return STATUS_INVALID_ARGUMENT;
    }

    let slices = unsafe { core::slice::from_raw_parts(descriptors, slice_count as usize) };
    let parallelism = rayon::current_num_threads().max(1);
    let mut total_bound = 0usize;

    for descriptor in slices {
        let mut overflowed = false;
        for_each_part(descriptor.length as usize, parallelism, |_, part_length| {
            if !overflowed {
                match total_bound.checked_add(codec::deflate_bound(DeflateFormat::Gzip, part_length)) {
                    Some(sum) => total_bound = sum,
                    None => overflowed = true,
                }
            }
        });
        if overflowed {
            return STATUS_INVALID_ARGUMENT;
        }
    }

    total_bound as i64
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn totem_gzip_compress_slices(
    level: i32,
    descriptors: *const ByteSliceDescriptor,
    slice_count: u32,
    staging: *mut u8,
    staging_capacity: u64,
) -> i64 {
    if !codec::is_valid_level(level) {
        return STATUS_INVALID_ARGUMENT;
    }
    if slice_count == 0 {
        return 0;
    }
    if descriptors.is_null() || !descriptors.is_aligned() || staging.is_null() {
        return STATUS_INVALID_ARGUMENT;
    }

    let slices = unsafe { core::slice::from_raw_parts(descriptors, slice_count as usize) };
    let parallelism = rayon::current_num_threads().max(1);
    let capacity = staging_capacity as usize;

    let mut planned_chunks = Vec::with_capacity((slice_count as usize).saturating_mul(2).max(16));
    let mut current_staging_offset = 0usize;

    for descriptor in slices {
        let slice_length = descriptor.length as usize;
        if slice_length == 0 {
            continue;
        }
        if descriptor.address == 0 {
            return STATUS_INVALID_ARGUMENT;
        }

        let base_address = descriptor.address as usize;
        let mut insufficient_space = false;

        for_each_part(slice_length, parallelism, |part_offset, part_length| {
            if insufficient_space {
                return;
            }
            let bound = codec::deflate_bound(DeflateFormat::Gzip, part_length);
            let Some(next_offset) = current_staging_offset.checked_add(bound) else {
                insufficient_space = true;
                return;
            };
            if next_offset > capacity {
                insufficient_space = true;
                return;
            }
            planned_chunks.push(PlannedChunk {
                address: base_address + part_offset,
                length: part_length,
                staging_offset: current_staging_offset,
                staging_bound: bound,
            });
            current_staging_offset = next_offset;
        });

        if insufficient_space {
            return STATUS_INSUFFICIENT_SPACE;
        }
    }

    if planned_chunks.is_empty() {
        return 0;
    }

    let staging_slice = unsafe { core::slice::from_raw_parts_mut(staging, current_staging_offset) };

    if planned_chunks.len() == 1 {
        let single_chunk = planned_chunks[0];
        let source_slice = unsafe { core::slice::from_raw_parts(single_chunk.address as *const u8, single_chunk.length) };
        return match codec::deflate_into_slice(DeflateFormat::Gzip, level, source_slice, staging_slice) {
            Some(produced) => produced as i64,
            None => STATUS_INSUFFICIENT_SPACE,
        };
    }

    let mut work_items: Vec<(&[u8], &mut [u8])> = Vec::with_capacity(planned_chunks.len());
    let mut remaining_staging = &mut staging_slice[..];
    for chunk in &planned_chunks {
        let source_slice = unsafe { core::slice::from_raw_parts(chunk.address as *const u8, chunk.length) };
        let (target_slot, rest) = remaining_staging.split_at_mut(chunk.staging_bound);
        remaining_staging = rest;
        work_items.push((source_slice, target_slot));
    }

    let mut produced_lengths = vec![0usize; planned_chunks.len()];
    let failed = AtomicBool::new(false);

    work_items
        .into_par_iter()
        .zip(produced_lengths.par_iter_mut())
        .for_each(|((source_slice, target_slot), produced_slot)| {
            if failed.load(Ordering::Relaxed) {
                return;
            }
            match codec::deflate_into_slice(DeflateFormat::Gzip, level, source_slice, target_slot) {
                Some(produced) => *produced_slot = produced,
                None => failed.store(true, Ordering::Relaxed),
            }
        });

    if failed.load(Ordering::Relaxed) {
        return STATUS_INSUFFICIENT_SPACE;
    }

    let mut compacted_offset = produced_lengths[0];
    for index in 1..planned_chunks.len() {
        let produced_length = produced_lengths[index];
        let source_offset = planned_chunks[index].staging_offset;
        if source_offset != compacted_offset && produced_length > 0 {
            staging_slice.copy_within(source_offset..source_offset + produced_length, compacted_offset);
        }
        compacted_offset += produced_length;
    }

    compacted_offset as i64
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn totem_gzip_inflate_members(
    source: *const u8,
    source_length: u64,
    member_offsets: *const i64,
    member_count: u32,
    destination: *mut u8,
    destination_length: u64,
) -> i64 {
    let count = member_count as usize;
    let source_bytes = source_length as usize;
    let destination_bytes = destination_length as usize;

    if count == 0
        || source.is_null()
        || member_offsets.is_null()
        || !member_offsets.is_aligned()
        || (destination.is_null() && destination_bytes > 0)
    {
        return STATUS_INVALID_ARGUMENT;
    }

    let source_slice = unsafe { core::slice::from_raw_parts(source, source_bytes) };
    let offsets = unsafe { core::slice::from_raw_parts(member_offsets, count) };
    if offsets[0] != 0 {
        return STATUS_BAD_DATA;
    }

    let mut member_ranges = Vec::with_capacity(count);
    let mut current_destination_offset = 0usize;

    for index in 0..count {
        let raw_start = offsets[index];
        if raw_start < 0 {
            return STATUS_BAD_DATA;
        }
        let start = raw_start as usize;
        let end = if index + 1 < count {
            let next_raw = offsets[index + 1];
            if next_raw < 0 {
                return STATUS_BAD_DATA;
            }
            next_raw as usize
        } else {
            source_bytes
        };

        if start >= end || end > source_bytes || end - start < 18 {
            return STATUS_BAD_DATA;
        }

        let trailer: [u8; 4] = source_slice[end - 4..end].try_into().unwrap();
        let member_uncompressed_length = u32::from_le_bytes(trailer) as usize;
        let Some(next_destination_offset) = current_destination_offset.checked_add(member_uncompressed_length) else {
            return STATUS_INSUFFICIENT_SPACE;
        };
        if next_destination_offset > destination_bytes {
            return STATUS_INSUFFICIENT_SPACE;
        }

        member_ranges.push((start, end, member_uncompressed_length));
        current_destination_offset = next_destination_offset;
    }

    if current_destination_offset != destination_bytes {
        return STATUS_BAD_DATA;
    }

    let destination_slice = match destination_bytes {
        0 => &mut [][..],
        length => unsafe { core::slice::from_raw_parts_mut(destination, length) },
    };

    let mut work_items: Vec<(&[u8], &mut [u8])> = Vec::with_capacity(count);
    let mut remaining_destination = destination_slice;
    for &(start, end, expected_length) in &member_ranges {
        let (member_destination, rest) = remaining_destination.split_at_mut(expected_length);
        remaining_destination = rest;
        work_items.push((&source_slice[start..end], member_destination));
    }

    let failed = AtomicBool::new(false);

    work_items.into_par_iter().for_each(|(member_bytes, member_destination)| {
        if failed.load(Ordering::Relaxed) {
            return;
        }
        let expected_length = member_destination.len();
        match codec::inflate_into_slice_ex(DeflateFormat::Gzip, member_bytes, member_destination) {
            Ok((consumed, produced)) if consumed == member_bytes.len() && produced == expected_length => {}
            _ => failed.store(true, Ordering::Relaxed),
        }
    });

    if failed.load(Ordering::Relaxed) {
        return STATUS_BAD_DATA;
    }

    destination_bytes as i64
}

#[inline]
fn for_each_part(slice_length: usize, parallelism: usize, mut action: impl FnMut(usize, usize)) {
    if slice_length == 0 {
        return;
    }
    if slice_length < PARALLEL_THRESHOLD_BYTES || parallelism <= 1 {
        action(0, slice_length);
        return;
    }

    let (partition_count, chunk_size) = split_parameters(slice_length, parallelism);
    for chunk_index in 0..partition_count {
        let start = chunk_index * chunk_size;
        let length = chunk_size.min(slice_length.saturating_sub(start));
        if length > 0 {
            action(start, length);
        }
    }
}

#[inline]
fn split_parameters(slice_length: usize, parallelism: usize) -> (usize, usize) {
    let max_allowed = MAX_CHUNK_SIZE_BYTES.max(slice_length.div_ceil(512));
    let ideal = (slice_length / (parallelism * 2)).clamp(MIN_CHUNK_SIZE_BYTES, max_allowed);
    let partition_count = slice_length.div_ceil(ideal).max(1);
    let chunk_size = slice_length.div_ceil(partition_count);
    (partition_count, chunk_size)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::gzip::scan_gzip_members;

    #[test]
    fn test_parallel_gzip_slices_and_member_inflation() {
        let part_a: Vec<u8> = (0..450_000u32).map(|index| ((index * 11) % 251) as u8).collect();
        let part_b: Vec<u8> = (0..380_000u32).map(|index| ((index * 23 + 3) % 253) as u8).collect();
        let descriptors = [
            ByteSliceDescriptor { address: part_a.as_ptr() as u64, length: part_a.len() as u64 },
            ByteSliceDescriptor { address: part_b.as_ptr() as u64, length: part_b.len() as u64 },
        ];

        let bound = unsafe { totem_gzip_slices_bound(descriptors.as_ptr(), descriptors.len() as u32) };
        assert!(bound > 0);
        let mut staging = vec![0u8; bound as usize];
        let compressed_length = unsafe {
            totem_gzip_compress_slices(
                1,
                descriptors.as_ptr(),
                descriptors.len() as u32,
                staging.as_mut_ptr(),
                staging.len() as u64,
            )
        };
        assert!(compressed_length > 0);

        let mut offsets = [0i64; 64];
        let member_count = unsafe {
            scan_gzip_members(staging.as_ptr(), compressed_length as u64, offsets.as_mut_ptr(), offsets.len() as u32)
        };
        assert!(member_count >= 2);

        let expected_length = part_a.len() + part_b.len();
        let mut restored = vec![0u8; expected_length];
        let inflated = unsafe {
            totem_gzip_inflate_members(
                staging.as_ptr(),
                compressed_length as u64,
                offsets.as_ptr(),
                member_count as u32,
                restored.as_mut_ptr(),
                restored.len() as u64,
            )
        };
        assert_eq!(inflated, expected_length as i64);
        assert_eq!(&restored[..part_a.len()], &part_a[..]);
        assert_eq!(&restored[part_a.len()..], &part_b[..]);
    }
}

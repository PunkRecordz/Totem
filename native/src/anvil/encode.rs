use rayon::prelude::*;

use super::frame::{self, CHUNK_HEADER_BYTES, COMPRESSION_ZLIB, MAX_CHUNK_SECTORS, SECTOR_BYTES};
use crate::ByteSliceDescriptor;
use crate::deflate::codec::{self, DeflateFormat};
use crate::deflate::{STATUS_INSUFFICIENT_SPACE, STATUS_INVALID_ARGUMENT};

pub const STATUS_CHUNK_TOO_LARGE: i64 = -5;

pub fn frame_bound(raw_length: usize) -> usize {
    frame::round_up(
        CHUNK_HEADER_BYTES + codec::deflate_bound(DeflateFormat::Zlib, raw_length),
        SECTOR_BYTES,
    )
}

fn frame_into_slice(level: i32, raw: &[u8], slot: &mut [u8]) -> i64 {
    if slot.len() < CHUNK_HEADER_BYTES {
        return STATUS_INSUFFICIENT_SPACE;
    }

    let Some(compressed) = codec::deflate_into_slice(
        DeflateFormat::Zlib,
        level,
        raw,
        &mut slot[CHUNK_HEADER_BYTES..],
    ) else {
        return STATUS_INSUFFICIENT_SPACE;
    };

    let framed_length = CHUNK_HEADER_BYTES + compressed;
    let padded_length = frame::round_up(framed_length, SECTOR_BYTES);
    if padded_length / SECTOR_BYTES > MAX_CHUNK_SECTORS {
        return STATUS_CHUNK_TOO_LARGE;
    }
    if padded_length > slot.len() {
        return STATUS_INSUFFICIENT_SPACE;
    }

    let header = ((compressed + 1) as u32).to_be_bytes();
    slot[..4].copy_from_slice(&header);
    slot[4] = COMPRESSION_ZLIB;
    slot[framed_length..padded_length].fill(0);

    padded_length as i64
}

#[unsafe(no_mangle)]
pub extern "C" fn totem_anvil_frame_bound(raw_length: u64) -> i64 {
    frame_bound(raw_length as usize) as i64
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn totem_anvil_frame_chunk(
    level: i32,
    source: *const u8,
    source_length: u64,
    destination: *mut u8,
    destination_capacity: u64,
) -> i64 {
    if !codec::is_valid_level(level) || source.is_null() || destination.is_null() {
        return STATUS_INVALID_ARGUMENT;
    }

    let raw = match source_length {
        0 => &[][..],
        length => unsafe { core::slice::from_raw_parts(source, length as usize) },
    };
    let slot = match destination_capacity {
        0 => &mut [][..],
        capacity => unsafe { core::slice::from_raw_parts_mut(destination, capacity as usize) },
    };

    frame_into_slice(level, raw, slot)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn totem_anvil_frame_chunks(
    level: i32,
    sources: *const ByteSliceDescriptor,
    count: u32,
    destination: *mut u8,
    destination_capacity: u64,
    sector_counts: *mut i32,
    parallel: u32,
) -> i64 {
    let count = count as usize;
    if count == 0 {
        return 0;
    }
    if !codec::is_valid_level(level)
        || sources.is_null()
        || !sources.is_aligned()
        || sector_counts.is_null()
        || !sector_counts.is_aligned()
    {
        return STATUS_INVALID_ARGUMENT;
    }

    let descriptors = unsafe { core::slice::from_raw_parts(sources, count) };
    let sector_counts = unsafe { core::slice::from_raw_parts_mut(sector_counts, count) };
    if descriptors.iter().any(|descriptor| descriptor.address == 0) {
        return STATUS_INVALID_ARGUMENT;
    }

    let mut slot_offsets = Vec::with_capacity(count + 1);
    let mut required = 0usize;
    for descriptor in descriptors {
        slot_offsets.push(required);
        let Some(updated) = required.checked_add(frame_bound(descriptor.length as usize)) else {
            return STATUS_INVALID_ARGUMENT;
        };
        required = updated;
    }
    slot_offsets.push(required);

    if destination.is_null() || required > destination_capacity as usize {
        return -(required as i64);
    }

    let destination_slice = unsafe { core::slice::from_raw_parts_mut(destination, required) };
    let mut work_items: Vec<(&[u8], &mut [u8])> = Vec::with_capacity(count);
    let mut remaining_output = &mut destination_slice[..];

    for (index, descriptor) in descriptors.iter().enumerate() {
        let raw_slice = match descriptor.length {
            0 => &[][..],
            length => unsafe { core::slice::from_raw_parts(descriptor.address as usize as *const u8, length as usize) },
        };
        let slot_capacity = slot_offsets[index + 1] - slot_offsets[index];
        let (slot_slice, rest) = remaining_output.split_at_mut(slot_capacity);
        remaining_output = rest;
        work_items.push((raw_slice, slot_slice));
    }

    let padded_lengths: Vec<i64> = match parallel != 0 && count > 1 {
        true => work_items
            .into_par_iter()
            .map(|(raw_slice, slot_slice)| frame_into_slice(level, raw_slice, slot_slice))
            .collect(),
        false => work_items
            .into_iter()
            .map(|(raw_slice, slot_slice)| frame_into_slice(level, raw_slice, slot_slice))
            .collect(),
    };

    // compaction only ever moves frames towards lower offsets inside destination_slice
    let mut compact_cursor = 0usize;
    for (index, &padded_length) in padded_lengths.iter().enumerate() {
        if padded_length < 0 {
            sector_counts[index] = padded_length as i32;
            continue;
        }

        let padded_length = padded_length as usize;
        let slot_start = slot_offsets[index];
        if slot_start != compact_cursor {
            destination_slice.copy_within(slot_start..slot_start + padded_length, compact_cursor);
        }

        sector_counts[index] = (padded_length / SECTOR_BYTES) as i32;
        compact_cursor += padded_length;
    }

    compact_cursor as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    fn inflate_frame(frame: &[u8]) -> Vec<u8> {
        let declared = u32::from_be_bytes(frame[..4].try_into().unwrap()) as usize;
        assert_eq!(frame[4], COMPRESSION_ZLIB);
        let mut scratch = Vec::with_capacity(1 << 20);
        codec::inflate_into_vec(DeflateFormat::Zlib, &frame[5..5 + declared - 1], &mut scratch, 1 << 26).unwrap();
        scratch
    }

    #[test]
    fn test_batch_frames_are_compacted_padded_and_round_trip() {
        let raws: Vec<Vec<u8>> = (1..30u32)
            .map(|seed| (0..(20_000 * seed)).map(|index| ((index ^ seed) % 53) as u8).collect())
            .collect();
        let sources: Vec<ByteSliceDescriptor> = raws
            .iter()
            .map(|raw| ByteSliceDescriptor {
                address: raw.as_ptr() as u64,
                length: raw.len() as u64,
            })
            .collect();

        for parallel in [0u32, 1] {
            let mut sector_counts = vec![0i32; raws.len()];
            let required = unsafe {
                totem_anvil_frame_chunks(
                    1,
                    sources.as_ptr(),
                    raws.len() as u32,
                    core::ptr::null_mut(),
                    0,
                    sector_counts.as_mut_ptr(),
                    parallel,
                )
            };
            assert!(required < 0);

            let mut destination = vec![0xAAu8; (-required) as usize];
            let total = unsafe {
                totem_anvil_frame_chunks(
                    1,
                    sources.as_ptr(),
                    raws.len() as u32,
                    destination.as_mut_ptr(),
                    destination.len() as u64,
                    sector_counts.as_mut_ptr(),
                    parallel,
                )
            };
            assert_eq!(total as usize % SECTOR_BYTES, 0);

            let mut cursor = 0usize;
            for (raw, &sectors) in raws.iter().zip(&sector_counts) {
                let frame_bytes = &destination[cursor..cursor + sectors as usize * SECTOR_BYTES];
                assert_eq!(&inflate_frame(frame_bytes), raw);

                let declared = u32::from_be_bytes(frame_bytes[..4].try_into().unwrap()) as usize;
                assert!(frame_bytes[4 + declared..].iter().all(|&byte| byte == 0));
                cursor += frame_bytes.len();
            }
            assert_eq!(cursor, total as usize);
        }
    }

    #[test]
    fn test_single_frame_matches_bound_contract() {
        let raw: Vec<u8> = (0..123_456u32).map(|index| (index % 11) as u8).collect();
        let mut destination = vec![0u8; totem_anvil_frame_bound(raw.len() as u64) as usize];
        let padded = unsafe {
            totem_anvil_frame_chunk(
                1,
                raw.as_ptr(),
                raw.len() as u64,
                destination.as_mut_ptr(),
                destination.len() as u64,
            )
        };
        assert!(padded > 0 && padded as usize % SECTOR_BYTES == 0);
        assert_eq!(inflate_frame(&destination[..padded as usize]), raw);

        let tiny = unsafe {
            totem_anvil_frame_chunk(1, raw.as_ptr(), raw.len() as u64, destination.as_mut_ptr(), 16)
        };
        assert_eq!(tiny, STATUS_INSUFFICIENT_SPACE);
    }
}

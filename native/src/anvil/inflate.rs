use core::sync::atomic::{AtomicUsize, Ordering};
use std::cell::RefCell;

use rayon::prelude::*;

use super::frame::{self, ChunkLocation, COMPRESSION_GZIP, COMPRESSION_NONE, COMPRESSION_ZLIB};
use crate::deflate::codec::{self, DeflateFormat, InflateError};

pub const ENTRY_ABSENT: i64 = -1;
pub const ENTRY_CORRUPT: i64 = -2;
pub const ENTRY_DEFERRED: i64 = -3;
pub const ENTRY_UNKNOWN_COMPRESSION: i64 = -4;

const OUTPUT_ALIGNMENT: usize = 64;
const INITIAL_SCRATCH_BYTES: usize = 1 << 20;
const MAX_RETAINED_SCRATCH_BYTES: usize = 4 << 20;
// oversized chunks are reported as corrupt so the jvm can replay through its streaming inflater
const MAX_BATCH_CHUNK_BYTES: usize = 256 << 20;

thread_local! {
    static INFLATE_SCRATCH: RefCell<Vec<u8>> = RefCell::new(Vec::with_capacity(INITIAL_SCRATCH_BYTES));
}

enum InPlaceOutcome {
    Produced([i64; 2]),
    Corrupt,
    RetryInScratch,
}

struct SharedOutput {
    base: *mut u8,
    capacity: usize,
    cursor: AtomicUsize,
}

// each task copies into a disjoint range reserved through the atomic cursor
unsafe impl Sync for SharedOutput {}

impl SharedOutput {
    fn publish(&self, bytes: &[u8]) -> [i64; 2] {
        let reserved = frame::round_up(bytes.len().max(1), OUTPUT_ALIGNMENT);
        let start = self.cursor.fetch_add(reserved, Ordering::Relaxed);

        let Some(end) = start.checked_add(bytes.len()) else {
            return [start as i64, ENTRY_DEFERRED];
        };
        if self.base.is_null() || end > self.capacity {
            return [start as i64, ENTRY_DEFERRED];
        }
        if !bytes.is_empty() {
            unsafe { core::ptr::copy_nonoverlapping(bytes.as_ptr(), self.base.add(start), bytes.len()) };
        }

        [start as i64, bytes.len() as i64]
    }

    // single-threaded fast path: inflates straight into the destination tail without copying through scratch
    fn inflate_in_place(&self, format: DeflateFormat, payload: &[u8]) -> InPlaceOutcome {
        let start = self.cursor.load(Ordering::Relaxed);
        let Some(available) = self.capacity.checked_sub(start) else {
            return InPlaceOutcome::RetryInScratch;
        };
        if self.base.is_null() || available == 0 {
            return InPlaceOutcome::RetryInScratch;
        }

        match unsafe { codec::inflate_into(format, payload, self.base.add(start), available) } {
            Ok(produced) => {
                self.cursor.store(
                    start + frame::round_up(produced.max(1), OUTPUT_ALIGNMENT),
                    Ordering::Relaxed,
                );
                InPlaceOutcome::Produced([start as i64, produced as i64])
            }
            Err(InflateError::BadData) => InPlaceOutcome::Corrupt,
            Err(InflateError::InsufficientSpace) => InPlaceOutcome::RetryInScratch,
        }
    }
}

fn decode_location(region: &[u8], location: ChunkLocation, output: &SharedOutput, in_place: bool) -> [i64; 2] {
    let (compression, payload_start, payload_length) = match location {
        ChunkLocation::Absent => return [0, ENTRY_ABSENT],
        ChunkLocation::UnknownCompression(code) => return [code as i64, ENTRY_UNKNOWN_COMPRESSION],
        ChunkLocation::Present { compression, payload_start, payload_length } => {
            (compression, payload_start, payload_length)
        }
    };

    let payload = &region[payload_start..payload_start + payload_length];
    let format = match compression {
        COMPRESSION_NONE => return output.publish(payload),
        COMPRESSION_ZLIB => DeflateFormat::Zlib,
        COMPRESSION_GZIP => DeflateFormat::Gzip,
        _ => return [compression as i64, ENTRY_UNKNOWN_COMPRESSION],
    };

    if in_place {
        match output.inflate_in_place(format, payload) {
            InPlaceOutcome::Produced(entry) => return entry,
            InPlaceOutcome::Corrupt => return [compression as i64, ENTRY_CORRUPT],
            InPlaceOutcome::RetryInScratch => {}
        }
    }

    INFLATE_SCRATCH.with_borrow_mut(|scratch| {
        let outcome = match codec::inflate_into_vec(format, payload, scratch, MAX_BATCH_CHUNK_BYTES) {
            Ok(_) => output.publish(scratch),
            Err(_) => [compression as i64, ENTRY_CORRUPT],
        };
        if scratch.capacity() > MAX_RETAINED_SCRATCH_BYTES {
            *scratch = Vec::with_capacity(INITIAL_SCRATCH_BYTES);
        }
        outcome
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn totem_anvil_inflate_chunks(
    region: *const u8,
    region_length: u64,
    first_chunk: u32,
    chunk_count: u32,
    destination: *mut u8,
    destination_capacity: u64,
    entries: *mut i64,
    parallel: u32,
) -> i64 {
    let first_chunk = first_chunk as usize;
    let chunk_count = chunk_count as usize;
    if region.is_null()
        || entries.is_null()
        || !entries.is_aligned()
        || first_chunk.saturating_add(chunk_count) > frame::CHUNKS_PER_REGION
    {
        return crate::deflate::STATUS_INVALID_ARGUMENT;
    }
    if destination.is_null() && destination_capacity != 0 {
        return crate::deflate::STATUS_INVALID_ARGUMENT;
    }

    let region = match region_length {
        0 => &[][..],
        length => unsafe { core::slice::from_raw_parts(region, length as usize) },
    };
    let entries = unsafe { core::slice::from_raw_parts_mut(entries as *mut [i64; 2], chunk_count) };
    let output = SharedOutput {
        base: destination,
        capacity: destination_capacity as usize,
        cursor: AtomicUsize::new(0),
    };

    let locate = |slot: usize| frame::locate_chunk(region, first_chunk + slot);
    match parallel != 0 && chunk_count > 1 {
        true => entries
            .par_iter_mut()
            .enumerate()
            .for_each(|(slot, entry)| *entry = decode_location(region, locate(slot), &output, false)),
        false => entries
            .iter_mut()
            .enumerate()
            .for_each(|(slot, entry)| *entry = decode_location(region, locate(slot), &output, true)),
    }

    let used = output.cursor.load(Ordering::Acquire);
    let deferred = entries.iter().any(|entry| entry[1] == ENTRY_DEFERRED);
    match deferred {
        true => -(used as i64),
        false => used as i64,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::anvil::frame::tests::build_region;

    fn zlib(payload: &[u8]) -> Vec<u8> {
        let mut compressed = vec![0u8; codec::deflate_bound(DeflateFormat::Zlib, payload.len())];
        let produced = codec::deflate_into_slice(DeflateFormat::Zlib, 6, payload, &mut compressed).unwrap();
        compressed.truncate(produced);
        compressed
    }

    #[test]
    fn test_batch_inflate_parallel_and_deferred_retry() {
        let payloads: Vec<Vec<u8>> = (0..40u32)
            .map(|seed| (0..150_000u32).map(|index| ((index * seed) % 97) as u8).collect())
            .collect();
        let compressed: Vec<Vec<u8>> = payloads.iter().map(|payload| zlib(payload)).collect();
        let chunk_specs: Vec<(usize, u8, &[u8], bool)> = compressed
            .iter()
            .enumerate()
            .map(|(index, bytes)| (index * 3, COMPRESSION_ZLIB, bytes.as_slice(), index % 2 == 0))
            .collect();
        let region = build_region(&chunk_specs);

        for parallel in [0u32, 1] {
            let mut entries = vec![0i64; 1024 * 2];
            let mut small = vec![0u8; 1 << 20];
            let first = unsafe {
                totem_anvil_inflate_chunks(
                    region.as_ptr(),
                    region.len() as u64,
                    0,
                    1024,
                    small.as_mut_ptr(),
                    small.len() as u64,
                    entries.as_mut_ptr(),
                    parallel,
                )
            };
            assert!(first < 0, "expected deferred chunks with a 1 MiB destination");

            let mut destination = vec![0u8; (-first) as usize];
            let used = unsafe {
                totem_anvil_inflate_chunks(
                    region.as_ptr(),
                    region.len() as u64,
                    0,
                    1024,
                    destination.as_mut_ptr(),
                    destination.len() as u64,
                    entries.as_mut_ptr(),
                    parallel,
                )
            };
            assert!(used > 0);

            for chunk_index in 0..1024usize {
                let offset = entries[chunk_index * 2] as usize;
                let length = entries[chunk_index * 2 + 1];
                match (chunk_index % 3 == 0 && chunk_index / 3 < payloads.len()).then(|| &payloads[chunk_index / 3]) {
                    Some(expected) => assert_eq!(&destination[offset..offset + length as usize], expected.as_slice()),
                    None => assert_eq!(length, ENTRY_ABSENT),
                }
            }
        }
    }

    #[test]
    fn test_batch_inflate_reports_corrupt_unknown_and_handles_null_destination_for_empty_payload() {
        let empty_zlib = zlib(&[]);
        let garbage = [0x78u8, 0x9C, 0xFF, 0xFF, 0xFF];
        let region = build_region(&[
            (0, COMPRESSION_ZLIB, &empty_zlib, true),
            (1, COMPRESSION_ZLIB, &garbage, true),
            (2, 42, b"xx", true),
            (3, COMPRESSION_NONE, b"raw!", false),
        ]);

        let mut sizing_entries = vec![0i64; 8];
        let required_neg = unsafe {
            totem_anvil_inflate_chunks(
                region.as_ptr(),
                region.len() as u64,
                0,
                4,
                core::ptr::null_mut(),
                0,
                sizing_entries.as_mut_ptr(),
                1,
            )
        };
        assert!(required_neg < 0);
        assert_eq!(sizing_entries[1], ENTRY_DEFERRED);

        for parallel in [0u32, 1] {
            let mut entries = vec![0i64; 8];
            let mut destination = vec![0u8; 4096];
            let used = unsafe {
                totem_anvil_inflate_chunks(
                    region.as_ptr(),
                    region.len() as u64,
                    0,
                    4,
                    destination.as_mut_ptr(),
                    destination.len() as u64,
                    entries.as_mut_ptr(),
                    parallel,
                )
            };
            assert!(used >= 4);
            assert_eq!(entries[1], 0);
            assert_eq!(entries[3], ENTRY_CORRUPT);
            assert_eq!(entries[4..6], [42, ENTRY_UNKNOWN_COMPRESSION]);
            assert_eq!(&destination[entries[6] as usize..entries[6] as usize + 4], b"raw!");
        }
    }
}

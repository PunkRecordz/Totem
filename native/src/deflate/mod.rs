pub mod codec;
pub mod gzip_batch;

pub use crate::ByteSliceDescriptor;
pub use gzip_batch::{totem_gzip_compress_slices, totem_gzip_inflate_members, totem_gzip_slices_bound};

use codec::{DeflateFormat, InflateError};

pub const STATUS_BAD_DATA: i64 = -1;
pub const STATUS_INSUFFICIENT_SPACE: i64 = -2;
pub const STATUS_INVALID_ARGUMENT: i64 = -3;

#[unsafe(no_mangle)]
pub unsafe extern "C" fn totem_inflate(
    format: u32,
    source: *const u8,
    source_length: u64,
    destination: *mut u8,
    destination_capacity: u64,
    consumed: *mut u64,
) -> i64 {
    let Some(format) = DeflateFormat::from_code(format) else {
        return STATUS_INVALID_ARGUMENT;
    };
    if source.is_null() || destination.is_null() {
        return STATUS_INVALID_ARGUMENT;
    }

    let source = match source_length {
        0 => &[][..],
        length => unsafe { core::slice::from_raw_parts(source, length as usize) },
    };
    match unsafe { codec::inflate_into_ex(format, source, destination, destination_capacity as usize) } {
        Ok((consumed_bytes, produced)) => {
            if !consumed.is_null() {
                unsafe { consumed.write_unaligned(consumed_bytes as u64) };
            }
            produced as i64
        }
        Err(InflateError::InsufficientSpace) => STATUS_INSUFFICIENT_SPACE,
        Err(InflateError::BadData) => STATUS_BAD_DATA,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn totem_deflate(
    format: u32,
    level: i32,
    source: *const u8,
    source_length: u64,
    destination: *mut u8,
    destination_capacity: u64,
) -> i64 {
    let Some(format) = DeflateFormat::from_code(format) else {
        return STATUS_INVALID_ARGUMENT;
    };
    if !codec::is_valid_level(level) || destination.is_null() || (source.is_null() && source_length != 0) {
        return STATUS_INVALID_ARGUMENT;
    }

    let source = match source_length {
        0 => &[][..],
        length => unsafe { core::slice::from_raw_parts(source, length as usize) },
    };
    match unsafe { codec::deflate_into(format, level, source, destination, destination_capacity as usize) } {
        Some(produced) => produced as i64,
        None => STATUS_INSUFFICIENT_SPACE,
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn totem_deflate_bound(format: u32, source_length: u64) -> i64 {
    match DeflateFormat::from_code(format) {
        Some(format) => codec::deflate_bound(format, source_length as usize) as i64,
        None => STATUS_INVALID_ARGUMENT,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_payload() -> Vec<u8> {
        (0..300_000u32).map(|index| ((index / 7) % 251) as u8 ^ (index % 3) as u8).collect()
    }

    #[test]
    fn test_round_trip_every_format_and_level() {
        let payload = sample_payload();

        for format_code in 0..3u32 {
            for level in [0, 1, 6, 12] {
                let bound = totem_deflate_bound(format_code, payload.len() as u64);
                let mut compressed = vec![0u8; bound as usize];
                let produced = unsafe {
                    totem_deflate(
                        format_code,
                        level,
                        payload.as_ptr(),
                        payload.len() as u64,
                        compressed.as_mut_ptr(),
                        compressed.len() as u64,
                    )
                };
                assert!(produced > 0, "format {format_code} level {level} failed: {produced}");

                let mut restored = vec![0u8; payload.len()];
                let inflated = unsafe {
                    totem_inflate(
                        format_code,
                        compressed.as_ptr(),
                        produced as u64,
                        restored.as_mut_ptr(),
                        restored.len() as u64,
                        core::ptr::null_mut(),
                    )
                };
                assert_eq!(inflated, payload.len() as i64);
                assert_eq!(restored, payload);
            }
        }
    }

    #[test]
    fn test_insufficient_space_and_bad_data_statuses() {
        let payload = sample_payload();
        let mut compressed = vec![0u8; totem_deflate_bound(1, payload.len() as u64) as usize];
        let produced = unsafe {
            totem_deflate(1, 1, payload.as_ptr(), payload.len() as u64, compressed.as_mut_ptr(), compressed.len() as u64)
        };

        let mut small = vec![0u8; 1024];
        let status = unsafe {
            totem_inflate(1, compressed.as_ptr(), produced as u64, small.as_mut_ptr(), small.len() as u64, core::ptr::null_mut())
        };
        assert_eq!(status, STATUS_INSUFFICIENT_SPACE);

        let garbage = [0x78u8, 0x9C, 0xFF, 0xFF, 0xFF, 0xFF];
        let status = unsafe {
            totem_inflate(1, garbage.as_ptr(), garbage.len() as u64, small.as_mut_ptr(), small.len() as u64, core::ptr::null_mut())
        };
        assert_eq!(status, STATUS_BAD_DATA);

        assert_eq!(totem_deflate_bound(9, 10), STATUS_INVALID_ARGUMENT);
    }

    #[test]
    fn test_inflate_into_vec_grows_scratch() {
        let payload = sample_payload();
        let mut compressed = vec![0u8; codec::deflate_bound(DeflateFormat::Zlib, payload.len())];
        let produced = codec::deflate_into_slice(DeflateFormat::Zlib, 1, &payload, &mut compressed).unwrap();

        let mut scratch = Vec::with_capacity(4096);
        let inflated = codec::inflate_into_vec(DeflateFormat::Zlib, &compressed[..produced], &mut scratch, 1 << 24);
        assert_eq!(inflated, Ok(payload.len()));
        assert_eq!(scratch, payload);
    }
}

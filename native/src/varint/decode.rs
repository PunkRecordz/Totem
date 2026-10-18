use super::decode_swar::{decode_single, decode_varint_shorts_swar};
#[cfg(target_arch = "x86_64")]
use super::decode_swar::{decode_single_from_packed, decode_varint_shorts_swar_4};

/// Decodes 32 or 64 VarInts at a time using AVX2, with branchless 16-wide decoding for mixed 1/2-byte streams.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
fn decode_varint_shorts_avx2(
    source: &[u8],
    destination: &mut [u16],
    mut source_offset: usize,
    mut destination_offset: usize,
) -> (usize, usize) {
    use crate::simd::{load_u8x32, store_u16x16, store_u16x8};
    use core::arch::x86_64::*;

    let mask_007f = _mm256_set1_epi16(0x007F);
    let mask_3f80 = _mm256_set1_epi16(0x3F80u16 as i16);
    let msb_mask = _mm256_set1_epi8(-128i8);

    let mask_007f_128 = _mm_set1_epi16(0x007F);
    let mask_3f80_128 = _mm_set1_epi16(0x3F80u16 as i16);

    let src_len = source.len();
    let dst_len = destination.len();

    while source_offset + 32 <= src_len && destination_offset + 32 <= dst_len {
        while let (Some(in_64), Some(out_64)) = (
            source
                .get(source_offset..source_offset + 64)
                .and_then(|s| s.first_chunk::<64>()),
            destination
                .get_mut(destination_offset..destination_offset + 64)
                .and_then(|d| d.first_chunk_mut::<64>()),
        ) {
            let (b0, b1_slice) = in_64.split_first_chunk::<32>().unwrap();
            let b1: &[u8; 32] = b1_slice.try_into().unwrap();
            let c0 = load_u8x32(b0);
            let c1 = load_u8x32(b1);
            let combined = _mm256_or_si256(c0, c1);

            if _mm256_testz_si256(combined, msb_mask) == 0 {
                break;
            }

            let s0_low = _mm256_cvtepu8_epi16(_mm256_castsi256_si128(c0));
            let s0_high = _mm256_cvtepu8_epi16(_mm256_extracti128_si256(c0, 1));
            let s1_low = _mm256_cvtepu8_epi16(_mm256_castsi256_si128(c1));
            let s1_high = _mm256_cvtepu8_epi16(_mm256_extracti128_si256(c1, 1));

            let (d0, r1) = out_64.split_first_chunk_mut::<16>().unwrap();
            let (d1, r2) = r1.split_first_chunk_mut::<16>().unwrap();
            let (d2, r3) = r2.split_first_chunk_mut::<16>().unwrap();
            let d3: &mut [u16; 16] = r3.try_into().unwrap();

            store_u16x16(d0, s0_low);
            store_u16x16(d1, s0_high);
            store_u16x16(d2, s1_low);
            store_u16x16(d3, s1_high);

            source_offset += 64;
            destination_offset += 64;
        }

        let (Some(in_32), Some(out_32)) = (
            source
                .get(source_offset..source_offset + 32)
                .and_then(|s| s.first_chunk::<32>()),
            destination
                .get_mut(destination_offset..destination_offset + 32)
                .and_then(|d| d.first_chunk_mut::<32>()),
        ) else {
            break;
        };

        let chunk = load_u8x32(in_32);
        let mask = _mm256_movemask_epi8(chunk) as u32;

        if mask == 0 {
            let low_128 = _mm256_castsi256_si128(chunk);
            let high_128 = _mm256_extracti128_si256(chunk, 1);

            let shorts_low = _mm256_cvtepu8_epi16(low_128);
            let shorts_high = _mm256_cvtepu8_epi16(high_128);

            let (d0, d1_slice) = out_32.split_first_chunk_mut::<16>().unwrap();
            store_u16x16(d0, shorts_low);
            store_u16x16(d1_slice.try_into().unwrap(), shorts_high);

            source_offset += 32;
            destination_offset += 32;
            continue;
        }

        if mask == 0x55555555 {
            let high_part = _mm256_and_si256(_mm256_srli_epi16(chunk, 1), mask_3f80);
            let low_part = _mm256_and_si256(chunk, mask_007f);
            let decoded_shorts = _mm256_or_si256(high_part, low_part);

            let (d0, _) = out_32.split_first_chunk_mut::<16>().unwrap();
            store_u16x16(d0, decoded_shorts);

            source_offset += 32;
            destination_offset += 16;
            continue;
        }

        if (mask & 0xFFFF) == 0x5555 {
            let low_128 = _mm256_castsi256_si128(chunk);
            let high_part = _mm_and_si128(_mm_srli_epi16(low_128, 1), mask_3f80_128);
            let low_part = _mm_and_si128(low_128, mask_007f_128);
            let decoded_shorts = _mm_or_si128(high_part, low_part);

            let (d0_8, _) = out_32.split_first_chunk_mut::<8>().unwrap();
            store_u16x8(d0_8, decoded_shorts);

            source_offset += 16;
            destination_offset += 8;
            continue;
        }

        if (mask & 0xFFFF) == 0 {
            let low_128 = _mm256_castsi256_si128(chunk);
            let shorts_16 = _mm256_cvtepu8_epi16(low_128);
            let (d0, _) = out_32.split_first_chunk_mut::<16>().unwrap();
            store_u16x16(d0, shorts_16);

            source_offset += 16;
            destination_offset += 16;
            continue;
        }

        if (mask & (mask >> 1)) == 0 {
            let mut local_off = 0usize;
            for slot in &mut out_32[..16] {
                let idx = local_off.min(30);
                let w = u16::from_le_bytes([in_32[idx], in_32[idx + 1]]);
                let is_two = (w >> 7) & 1;
                let high_mask = 0u16.wrapping_sub(is_two);
                let val = (w & 0x007F) | (((w >> 1) & 0x3F80) & high_mask);
                *slot = val;
                local_off += 1 + (is_two as usize);
            }
            source_offset += local_off;
            destination_offset += 16;
            continue;
        }

        let prefix_ones = mask.trailing_zeros() as usize;
        if prefix_ones > 0 {
            for (dst_slot, &src_byte) in out_32[..prefix_ones]
                .iter_mut()
                .zip(&in_32[..prefix_ones])
            {
                *dst_slot = u16::from(src_byte);
            }
            source_offset += prefix_ones;
            destination_offset += prefix_ones;
        }

        if let (Some(bytes), Some(slot)) = (
            source
                .get(source_offset..source_offset + 4)
                .and_then(|s| s.first_chunk::<4>()),
            destination.get_mut(destination_offset),
        ) {
            let packed = u32::from_le_bytes(*bytes);
            match decode_single_from_packed(packed, source_offset) {
                Some((value, new_src)) => {
                    *slot = value;
                    source_offset = new_src;
                    destination_offset += 1;
                }
                None => break,
            }
        } else {
            break;
        }
    }

    (source_offset, destination_offset)
}

/// Decodes VarInt bytes from `src_ptr` into shorts at `dest_ptr`.
/// Returns the number of bytes read from `src_ptr` on success (>= 0),
/// or a negative error code:
/// -1: Truncated input (a VarInt was cut off before completing).
/// -2: Incomplete output (buffer contained fewer VarInts than expected_size).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn decode_varint_shorts(
    src_ptr: *const u8,
    src_len: u64,
    dest_ptr: *mut u16,
    expected_size: u32,
) -> i64 {
    if src_ptr.is_null() || dest_ptr.is_null() {
        return -1;
    }

    let source = unsafe { core::slice::from_raw_parts(src_ptr, src_len as usize) };
    let destination = unsafe { core::slice::from_raw_parts_mut(dest_ptr, expected_size as usize) };

    decode_varint_shorts_slice(source, destination)
}

fn decode_varint_shorts_slice(source: &[u8], destination: &mut [u16]) -> i64 {
    #[cfg(target_arch = "x86_64")]
    let (mut source_offset, mut destination_offset) = if is_x86_feature_detected!("avx2") {
        let (so, doff) = unsafe { decode_varint_shorts_avx2(source, destination, 0, 0) };
        decode_varint_shorts_swar_4(source, destination, so, doff)
    } else {
        decode_varint_shorts_swar(source, destination, 0, 0)
    };

    #[cfg(not(target_arch = "x86_64"))]
    let (mut source_offset, mut destination_offset) =
        decode_varint_shorts_swar(source, destination, 0, 0);

    while source_offset < source.len() && destination_offset < destination.len() {
        match decode_single(source, &mut source_offset) {
            Some(value) => {
                destination[destination_offset] = value;
                destination_offset += 1;
            }
            None => return -1,
        }
    }

    if destination_offset < destination.len() {
        return -2;
    }

    source_offset as i64
}

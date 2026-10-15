use super::decode_swar::{decode_single, decode_varint_shorts_swar};
#[cfg(target_arch = "x86_64")]
use super::decode_swar::{decode_single_from_packed, decode_varint_shorts_swar_4};

/// Decodes 32 or 64 VarInts at a time using AVX2, with branchless 8-wide decoding for mixed 1/2-byte streams.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn decode_varint_shorts_avx2(
    source: &[u8],
    destination: &mut [u16],
    mut source_offset: usize,
    mut destination_offset: usize,
) -> (usize, usize) {
    use core::arch::x86_64::*;

    let mask_007f = _mm256_set1_epi16(0x007F);
    let mask_3f80 = _mm256_set1_epi16(0x3F80u16 as i16);
    let msb_mask = _mm256_set1_epi8(-128i8);

    let mask_007f_128 = _mm_set1_epi16(0x007F);
    let mask_3f80_128 = _mm_set1_epi16(0x3F80u16 as i16);

    let src_ptr = source.as_ptr();
    let dst_ptr = destination.as_mut_ptr();
    let src_len = source.len();
    let dst_len = destination.len();

    while source_offset + 32 <= src_len && destination_offset + 32 <= dst_len {
        while source_offset + 64 <= src_len && destination_offset + 64 <= dst_len {
            let c0 = _mm256_loadu_si256(src_ptr.add(source_offset) as *const __m256i);
            let c1 = _mm256_loadu_si256(src_ptr.add(source_offset + 32) as *const __m256i);
            let combined = _mm256_or_si256(c0, c1);

            if _mm256_testz_si256(combined, msb_mask) == 0 {
                break;
            }

            let s0_low = _mm256_cvtepu8_epi16(_mm256_castsi256_si128(c0));
            let s0_high = _mm256_cvtepu8_epi16(_mm256_extracti128_si256(c0, 1));
            let s1_low = _mm256_cvtepu8_epi16(_mm256_castsi256_si128(c1));
            let s1_high = _mm256_cvtepu8_epi16(_mm256_extracti128_si256(c1, 1));

            let out_ptr = dst_ptr.add(destination_offset) as *mut __m256i;
            _mm256_storeu_si256(out_ptr, s0_low);
            _mm256_storeu_si256(out_ptr.add(1), s0_high);
            _mm256_storeu_si256(out_ptr.add(2), s1_low);
            _mm256_storeu_si256(out_ptr.add(3), s1_high);

            source_offset += 64;
            destination_offset += 64;
        }

        if source_offset + 32 > src_len || destination_offset + 32 > dst_len {
            break;
        }

        let chunk = _mm256_loadu_si256(src_ptr.add(source_offset) as *const __m256i);
        let mask = _mm256_movemask_epi8(chunk) as u32;

        if mask == 0 {
            let low_128 = _mm256_castsi256_si128(chunk);
            let high_128 = _mm256_extracti128_si256(chunk, 1);

            let shorts_low = _mm256_cvtepu8_epi16(low_128);
            let shorts_high = _mm256_cvtepu8_epi16(high_128);

            let out_ptr = dst_ptr.add(destination_offset) as *mut __m256i;
            _mm256_storeu_si256(out_ptr, shorts_low);
            _mm256_storeu_si256(out_ptr.add(1), shorts_high);

            source_offset += 32;
            destination_offset += 32;
            continue;
        }

        if mask == 0x55555555 {
            let high_part = _mm256_and_si256(_mm256_srli_epi16(chunk, 1), mask_3f80);
            let low_part = _mm256_and_si256(chunk, mask_007f);
            let decoded_shorts = _mm256_or_si256(high_part, low_part);

            let out_ptr = dst_ptr.add(destination_offset) as *mut __m256i;
            _mm256_storeu_si256(out_ptr, decoded_shorts);

            source_offset += 32;
            destination_offset += 16;
            continue;
        }

        if (mask & 0xFFFF) == 0x5555 {
            let low_128 = _mm256_castsi256_si128(chunk);
            let high_part = _mm_and_si128(_mm_srli_epi16(low_128, 1), mask_3f80_128);
            let low_part = _mm_and_si128(low_128, mask_007f_128);
            let decoded_shorts = _mm_or_si128(high_part, low_part);

            let out_ptr = dst_ptr.add(destination_offset) as *mut __m128i;
            _mm_storeu_si128(out_ptr, decoded_shorts);

            source_offset += 16;
            destination_offset += 8;
            continue;
        }

        if (mask & 0xFFFF) == 0 {
            let low_128 = _mm256_castsi256_si128(chunk);
            let shorts_16 = _mm256_cvtepu8_epi16(low_128);
            _mm256_storeu_si256(dst_ptr.add(destination_offset) as *mut __m256i, shorts_16);

            source_offset += 16;
            destination_offset += 16;
            continue;
        }

        if (mask & (mask >> 1)) == 0 {
            for _ in 0..8 {
                let w = u16::from_le(core::ptr::read_unaligned(src_ptr.add(source_offset) as *const u16));
                let is_two = (w >> 7) & 1;
                let high_mask = 0u16.wrapping_sub(is_two);
                let val = (w & 0x007F) | (((w >> 1) & 0x3F80) & high_mask);
                *dst_ptr.add(destination_offset) = val;
                source_offset += 1 + (is_two as usize);
                destination_offset += 1;
            }
            continue;
        }

        let prefix_ones = mask.trailing_zeros() as usize;
        if prefix_ones > 0 {
            for i in 0..prefix_ones {
                *dst_ptr.add(destination_offset + i) = *src_ptr.add(source_offset + i) as u16;
            }
            source_offset += prefix_ones;
            destination_offset += prefix_ones;
        }

        if source_offset + 4 <= src_len && destination_offset < dst_len {
            let pointer = src_ptr.add(source_offset) as *const u32;
            let packed = u32::from_le(core::ptr::read_unaligned(pointer));
            match decode_single_from_packed(packed, source_offset) {
                Some((value, new_src)) => {
                    *dst_ptr.add(destination_offset) = value;
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
    let source = unsafe { core::slice::from_raw_parts(src_ptr, src_len as usize) };
    let destination = unsafe { core::slice::from_raw_parts_mut(dest_ptr, expected_size as usize) };

    #[cfg(target_arch = "x86_64")]
    let (source_offset, destination_offset) = if is_x86_feature_detected!("avx2") {
        let (so, doff) = unsafe { decode_varint_shorts_avx2(source, destination, 0, 0) };
        unsafe { decode_varint_shorts_swar_4(source, destination, so, doff) }
    } else {
        unsafe { decode_varint_shorts_swar(source, destination, 0, 0) }
    };

    #[cfg(not(target_arch = "x86_64"))]
    let (source_offset, destination_offset) =
        unsafe { decode_varint_shorts_swar(source, destination, 0, 0) };

    let mut source_offset = source_offset;
    let mut destination_offset = destination_offset;

    while source_offset < source.len() && destination_offset < destination.len() {
        match unsafe { decode_single(source, &mut source_offset) } {
            Some(value) => {
                unsafe { *destination.get_unchecked_mut(destination_offset) = value };
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

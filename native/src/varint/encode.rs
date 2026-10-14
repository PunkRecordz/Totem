use super::encode_swar::{
    encode_parallel_4, encode_parallel_4_two_bytes, encode_single, encode_single_branchless,
};

/// AVX2 encoder processing up to 64 elements at once, with branchless 16-wide encoding for mixed 1/2-byte streams.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn encode_shorts_varint_avx2(
    source: &[u16],
    destination: &mut [u8],
    mut source_offset: usize,
    mut destination_offset: usize,
) -> (usize, usize) {
    use core::arch::x86_64::*;

    let mask_ge_128 = _mm256_set1_epi16(0xFF80u16 as i16);
    let mask_ge_16384 = _mm256_set1_epi16(0xC000u16 as i16);
    let all_ones = _mm256_set1_epi32(-1);
    let mask_127 = _mm256_set1_epi16(127);
    let mask_7f00 = _mm256_set1_epi16(0x7F00u16 as i16);
    let mask_007f = _mm256_set1_epi16(0x007F);
    let mask_0080 = _mm256_set1_epi16(0x0080u16 as i16);

    let src_ptr = source.as_ptr();
    let dst_ptr = destination.as_mut_ptr();
    let src_len = source.len();

    while source_offset + 32 <= src_len {
        while source_offset + 64 <= src_len {
            let u0 = _mm256_loadu_si256(src_ptr.add(source_offset) as *const __m256i);
            let u1 = _mm256_loadu_si256(src_ptr.add(source_offset + 16) as *const __m256i);
            let u2 = _mm256_loadu_si256(src_ptr.add(source_offset + 32) as *const __m256i);
            let u3 = _mm256_loadu_si256(src_ptr.add(source_offset + 48) as *const __m256i);

            let or_all = _mm256_or_si256(_mm256_or_si256(u0, u1), _mm256_or_si256(u2, u3));
            if _mm256_testz_si256(or_all, mask_ge_128) == 0 {
                break;
            }

            let packed01 = _mm256_permute4x64_epi64(_mm256_packus_epi16(u0, u1), 0xD8);
            let packed23 = _mm256_permute4x64_epi64(_mm256_packus_epi16(u2, u3), 0xD8);

            let out_ptr = dst_ptr.add(destination_offset) as *mut __m256i;
            _mm256_storeu_si256(out_ptr, packed01);
            _mm256_storeu_si256(out_ptr.add(1), packed23);

            source_offset += 64;
            destination_offset += 64;
        }

        if source_offset + 32 > src_len {
            break;
        }

        let v0 = _mm256_loadu_si256(src_ptr.add(source_offset) as *const __m256i);
        let v1 = _mm256_loadu_si256(src_ptr.add(source_offset + 16) as *const __m256i);
        let or_combined = _mm256_or_si256(v0, v1);

        if _mm256_testz_si256(or_combined, mask_ge_128) != 0 {
            let packed = _mm256_packus_epi16(v0, v1);
            let in_order = _mm256_permute4x64_epi64(packed, 0xD8);
            _mm256_storeu_si256(dst_ptr.add(destination_offset) as *mut __m256i, in_order);
            source_offset += 32;
            destination_offset += 32;
            continue;
        }

        if _mm256_testz_si256(v0, mask_ge_128) != 0 {
            let bytes_0_15 =
                _mm_packus_epi16(_mm256_castsi256_si128(v0), _mm256_extracti128_si256(v0, 1));
            _mm_storeu_si128(dst_ptr.add(destination_offset) as *mut __m128i, bytes_0_15);
            source_offset += 16;
            destination_offset += 16;
            continue;
        }

        if _mm256_testz_si256(or_combined, mask_ge_16384) != 0 {
            let cmp0 = _mm256_cmpgt_epi16(v0, mask_127);
            let cmp1 = _mm256_cmpgt_epi16(v1, mask_127);
            let cmp_both = _mm256_and_si256(cmp0, cmp1);

            if _mm256_testc_si256(cmp_both, all_ones) != 0 {
                let encoded_0 = _mm256_or_si256(
                    _mm256_and_si256(_mm256_slli_epi16(v0, 1), mask_7f00),
                    _mm256_or_si256(_mm256_and_si256(v0, mask_007f), mask_0080),
                );
                let encoded_1 = _mm256_or_si256(
                    _mm256_and_si256(_mm256_slli_epi16(v1, 1), mask_7f00),
                    _mm256_or_si256(_mm256_and_si256(v1, mask_007f), mask_0080),
                );
                let out_ptr = dst_ptr.add(destination_offset);
                _mm256_storeu_si256(out_ptr as *mut __m256i, encoded_0);
                _mm256_storeu_si256(out_ptr.add(32) as *mut __m256i, encoded_1);
                source_offset += 32;
                destination_offset += 64;
                continue;
            }

            if _mm256_testc_si256(cmp0, all_ones) != 0 {
                let encoded_0 = _mm256_or_si256(
                    _mm256_and_si256(_mm256_slli_epi16(v0, 1), mask_7f00),
                    _mm256_or_si256(_mm256_and_si256(v0, mask_007f), mask_0080),
                );
                _mm256_storeu_si256(dst_ptr.add(destination_offset) as *mut __m256i, encoded_0);
                source_offset += 16;
                destination_offset += 32;
                continue;
            }

            for _ in 0..16 {
                let value = *src_ptr.add(source_offset);
                let is_two = (value >= 128) as usize;
                let b0 = (value & 0x7F) | ((is_two as u16) << 7);
                let out = b0 | ((value >> 7) << 8);
                core::ptr::write_unaligned(dst_ptr.add(destination_offset) as *mut u16, out.to_le());
                source_offset += 1;
                destination_offset += 1 + is_two;
            }
            continue;
        }

        let value = *src_ptr.add(source_offset);
        source_offset += 1;
        destination_offset = encode_single_branchless(value, destination, destination_offset);
    }

    (source_offset, destination_offset)
}

/// Encodes shorts from `src_ptr` into VarInt bytes at `dest_ptr` (up to `dest_len` bytes).
/// Returns the number of bytes written to `dest_ptr`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn encode_shorts_varint(
    src_ptr: *const u16,
    src_len: u64,
    dest_ptr: *mut u8,
    dest_len: u64,
) -> u64 {
    let source = unsafe { core::slice::from_raw_parts(src_ptr, src_len as usize) };
    let destination = unsafe { core::slice::from_raw_parts_mut(dest_ptr, dest_len as usize) };

    #[cfg(target_arch = "x86_64")]
    let (mut source_offset, mut destination_offset) = if is_x86_feature_detected!("avx2") {
        unsafe { encode_shorts_varint_avx2(source, destination, 0, 0) }
    } else {
        (0, 0)
    };

    #[cfg(not(target_arch = "x86_64"))]
    let (mut source_offset, mut destination_offset) = (0usize, 0usize);

    let safe_end = source.len().saturating_sub(4);
    while source_offset < safe_end {
        if source_offset + 4 <= safe_end {
            if let Some((new_src, new_dest)) =
                unsafe { encode_parallel_4(source, source_offset, destination, destination_offset) }
            {
                source_offset = new_src;
                destination_offset = new_dest;
                continue;
            }
            if let Some((new_src, new_dest)) = unsafe {
                encode_parallel_4_two_bytes(source, source_offset, destination, destination_offset)
            } {
                source_offset = new_src;
                destination_offset = new_dest;
                continue;
            }
        }
        let value = unsafe { *source.get_unchecked(source_offset) };
        source_offset += 1;
        destination_offset =
            unsafe { encode_single_branchless(value, destination, destination_offset) };
    }

    while source_offset < source.len() {
        let value = unsafe { *source.get_unchecked(source_offset) };
        source_offset += 1;
        unsafe { encode_single(value, destination, &mut destination_offset) };
    }

    destination_offset as u64
}

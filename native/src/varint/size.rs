/// Calculates the exact number of bytes required to encode `src_len` shorts as VarInt.
/// Highly optimized with AVX2 SIMD on x86_64.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn varint_encoded_size_avx2(src: *const u16, len: usize) -> usize {
    use core::arch::x86_64::*;

    let mut offset = 0;
    let mut extra_bytes = 0usize;
    let mask_ge_128 = _mm256_set1_epi16(0xFF80u16 as i16);
    let mask_ge_16384 = _mm256_set1_epi16(0xC000u16 as i16);
    let zero = _mm256_setzero_si256();

    while offset + 64 <= len {
        let pointer = src.add(offset);
        let v0 = _mm256_loadu_si256(pointer as *const __m256i);
        let v1 = _mm256_loadu_si256(pointer.add(16) as *const __m256i);
        let v2 = _mm256_loadu_si256(pointer.add(32) as *const __m256i);
        let v3 = _mm256_loadu_si256(pointer.add(48) as *const __m256i);

        let v_or_01 = _mm256_or_si256(v0, v1);
        let v_or_23 = _mm256_or_si256(v2, v3);
        let v_or = _mm256_or_si256(v_or_01, v_or_23);

        if _mm256_testz_si256(v_or, mask_ge_128) != 0 {
            offset += 64;
            continue;
        }

        let has_ge_16384 = _mm256_testz_si256(v_or, mask_ge_16384) == 0;

        extra_bytes += count_mask_exceeding_avx2(v0, v1, v2, v3, mask_ge_128, zero);

        if has_ge_16384 {
            extra_bytes += count_mask_exceeding_avx2(v0, v1, v2, v3, mask_ge_16384, zero);
        }

        offset += 64;
    }

    while offset + 16 <= len {
        let v = _mm256_loadu_si256(src.add(offset) as *const __m256i);

        if _mm256_testz_si256(v, mask_ge_128) != 0 {
            offset += 16;
            continue;
        }

        let t128 = _mm256_and_si256(v, mask_ge_128);
        let eq_zero_128 = _mm256_cmpeq_epi16(t128, zero);
        let mask_128 = _mm256_movemask_epi8(eq_zero_128) as u32;
        extra_bytes += 16 - (mask_128.count_ones() / 2) as usize;

        if _mm256_testz_si256(v, mask_ge_16384) == 0 {
            let t16384 = _mm256_and_si256(v, mask_ge_16384);
            let eq_zero_16384 = _mm256_cmpeq_epi16(t16384, zero);
            let mask_16384 = _mm256_movemask_epi8(eq_zero_16384) as u32;
            extra_bytes += 16 - (mask_16384.count_ones() / 2) as usize;
        }

        offset += 16;
    }

    while offset < len {
        let val = *src.add(offset);
        if val >= 128 {
            extra_bytes += 1;
            if val >= 16384 {
                extra_bytes += 1;
            }
        }
        offset += 1;
    }

    len + extra_bytes
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
unsafe fn count_mask_exceeding_avx2(
    v0: core::arch::x86_64::__m256i,
    v1: core::arch::x86_64::__m256i,
    v2: core::arch::x86_64::__m256i,
    v3: core::arch::x86_64::__m256i,
    threshold_mask: core::arch::x86_64::__m256i,
    zero: core::arch::x86_64::__m256i,
) -> usize {
    use core::arch::x86_64::*;

    let m0 = _mm256_movemask_epi8(_mm256_cmpeq_epi16(_mm256_and_si256(v0, threshold_mask), zero)) as u32;
    let m1 = _mm256_movemask_epi8(_mm256_cmpeq_epi16(_mm256_and_si256(v1, threshold_mask), zero)) as u32;
    let m2 = _mm256_movemask_epi8(_mm256_cmpeq_epi16(_mm256_and_si256(v2, threshold_mask), zero)) as u32;
    let m3 = _mm256_movemask_epi8(_mm256_cmpeq_epi16(_mm256_and_si256(v3, threshold_mask), zero)) as u32;

    let m01 = (m0 as u64) | ((m1 as u64) << 32);
    let m23 = (m2 as u64) | ((m3 as u64) << 32);

    64 - ((m01.count_ones() + m23.count_ones()) / 2) as usize
}

/// Fallback calculation for encoded VarInt size using 64-bit SWAR.
#[inline(always)]
unsafe fn varint_encoded_size_swar(src: *const u16, len: usize) -> usize {
    let mut offset = 0;
    let mut extra_bytes = 0usize;

    while offset + 4 <= len {
        let pointer = src.add(offset) as *const u64;
        let packed = u64::from_le(core::ptr::read_unaligned(pointer));

        if (packed & 0xFF80FF80FF80FF80) == 0 {
            offset += 4;
            continue;
        }

        for i in 0..4 {
            let val = (packed >> (i * 16)) as u16;
            if val >= 128 {
                extra_bytes += 1;
                if val >= 16384 {
                    extra_bytes += 1;
                }
            }
        }
        offset += 4;
    }

    while offset < len {
        let val = *src.add(offset);
        if val >= 128 {
            extra_bytes += 1;
            if val >= 16384 {
                extra_bytes += 1;
            }
        }
        offset += 1;
    }

    len + extra_bytes
}

/// Calculates the exact number of bytes required to encode `src_len` shorts as VarInt.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn varint_encoded_size(
    src_ptr: *const u16,
    src_len: u64,
) -> u64 {
    let len = src_len as usize;

    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        return unsafe { varint_encoded_size_avx2(src_ptr, len) } as u64;
    }

    unsafe { varint_encoded_size_swar(src_ptr, len) as u64 }
}

/// Calculates the exact number of bytes required to encode `src_len` shorts as VarInt.
/// Highly optimized with AVX2 SIMD on x86_64.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
fn varint_encoded_size_avx2(src: &[u16]) -> usize {
    use crate::simd::load_u16x16;
    use core::arch::x86_64::*;

    let mut extra_bytes = 0usize;
    let mask_ge_128 = _mm256_set1_epi16(0xFF80u16 as i16);
    let mask_ge_16384 = _mm256_set1_epi16(0xC000u16 as i16);
    let zero = _mm256_setzero_si256();

    let (blocks_64, after_64) = src.as_chunks::<64>();
    for block in blocks_64 {
        let (c0, r1) = block.split_first_chunk::<16>().unwrap();
        let (c1, r2) = r1.split_first_chunk::<16>().unwrap();
        let (c2, r3) = r2.split_first_chunk::<16>().unwrap();
        let c3: &[u16; 16] = r3.try_into().unwrap();

        let v0 = load_u16x16(c0);
        let v1 = load_u16x16(c1);
        let v2 = load_u16x16(c2);
        let v3 = load_u16x16(c3);

        let v_or_01 = _mm256_or_si256(v0, v1);
        let v_or_23 = _mm256_or_si256(v2, v3);
        let v_or = _mm256_or_si256(v_or_01, v_or_23);

        if _mm256_testz_si256(v_or, mask_ge_128) != 0 {
            continue;
        }

        let has_ge_16384 = _mm256_testz_si256(v_or, mask_ge_16384) == 0;

        extra_bytes += count_mask_exceeding_avx2(v0, v1, v2, v3, mask_ge_128, zero);

        if has_ge_16384 {
            extra_bytes += count_mask_exceeding_avx2(v0, v1, v2, v3, mask_ge_16384, zero);
        }
    }

    let (blocks_16, tail) = after_64.as_chunks::<16>();
    for chunk in blocks_16 {
        let v = load_u16x16(chunk);

        if _mm256_testz_si256(v, mask_ge_128) != 0 {
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
    }

    let processed = blocks_64.len() * 64 + blocks_16.len() * 16;
    processed + extra_bytes + varint_encoded_size_swar(tail)
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
fn count_mask_exceeding_avx2(
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
fn varint_encoded_size_swar(src: &[u16]) -> usize {
    let mut extra_bytes = 0usize;
    let (chunks, remainder) = src.as_chunks::<4>();

    for &[s0, s1, s2, s3] in chunks {
        let packed = (s0 as u64)
            | ((s1 as u64) << 16)
            | ((s2 as u64) << 32)
            | ((s3 as u64) << 48);

        if (packed & 0xFF80FF80FF80FF80) == 0 {
            continue;
        }

        for val in [s0, s1, s2, s3] {
            if val >= 128 {
                extra_bytes += 1;
                if val >= 16384 {
                    extra_bytes += 1;
                }
            }
        }
    }

    for &val in remainder {
        if val >= 128 {
            extra_bytes += 1;
            if val >= 16384 {
                extra_bytes += 1;
            }
        }
    }

    src.len() + extra_bytes
}

/// Calculates the exact number of bytes required to encode `src_len` shorts as VarInt.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn varint_encoded_size(
    src_ptr: *const u16,
    src_len: u64,
) -> u64 {
    if src_ptr.is_null() || src_len == 0 {
        return 0;
    }

    let src = unsafe { core::slice::from_raw_parts(src_ptr, src_len as usize) };

    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        return unsafe { varint_encoded_size_avx2(src) } as u64;
    }

    varint_encoded_size_swar(src) as u64
}

/// Counts how many shorts in `src[0..len]` have `ignore_lut[short] == 0`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn count_matching_shorts(
    src_ptr: *const u16,
    len: u64,
    ignore_lut_ptr: *const u8,
) -> u64 {
    if src_ptr.is_null() || len == 0 || ignore_lut_ptr.is_null() {
        return 0;
    }

    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        return count_matching_shorts_avx2(src_ptr, len as usize, ignore_lut_ptr) as u64;
    }

    count_matching_shorts_scalar(src_ptr, len as usize, ignore_lut_ptr) as u64
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
unsafe fn try_skip_ignored_uniform_run(
    src: *const u16,
    len: usize,
    i: usize,
    c0: core::arch::x86_64::__m256i,
    c1: core::arch::x86_64::__m256i,
    lut: *const u8,
    lut_0: u8,
) -> usize {
    use core::arch::x86_64::*;

    let or_combined = _mm256_or_si256(c0, c1);
    if lut_0 != 0 && _mm256_testz_si256(or_combined, or_combined) != 0 {
        let mut zero_run = 32usize;
        while i + zero_run + 32 <= len {
            let next_p = src.add(i + zero_run);
            let nc0 = _mm256_loadu_si256(next_p as *const __m256i);
            let nc1 = _mm256_loadu_si256(next_p.add(16) as *const __m256i);
            let next_or = _mm256_or_si256(nc0, nc1);
            if _mm256_testz_si256(next_or, next_or) == 0 {
                break;
            }
            zero_run += 32;
        }
        return zero_run;
    }

    let first_short = *src.add(i);
    if *lut.add(first_short as usize) != 0 {
        let v_first = _mm256_set1_epi16(first_short as i16);
        let cmp0 = _mm256_cmpeq_epi16(c0, v_first);
        let cmp1 = _mm256_cmpeq_epi16(c1, v_first);
        let cmp_both = _mm256_and_si256(cmp0, cmp1);
        if _mm256_movemask_epi8(cmp_both) as u32 == 0xFFFF_FFFF {
            let mut uniform_run = 32usize;
            while i + uniform_run + 32 <= len {
                let next_p = src.add(i + uniform_run);
                let nc0 = _mm256_loadu_si256(next_p as *const __m256i);
                let nc1 = _mm256_loadu_si256(next_p.add(16) as *const __m256i);
                let c0 = _mm256_cmpeq_epi16(nc0, v_first);
                let c1 = _mm256_cmpeq_epi16(nc1, v_first);
                if _mm256_movemask_epi8(_mm256_and_si256(c0, c1)) as u32 != 0xFFFF_FFFF {
                    break;
                }
                uniform_run += 32;
            }
            return uniform_run;
        }
    }

    0
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn count_matching_shorts_avx2(src: *const u16, len: usize, lut: *const u8) -> usize {
    use core::arch::x86_64::*;

    let lut_0 = *lut;
    let mut i = 0;
    let mut count = 0usize;

    while i + 32 <= len {
        let p = src.add(i);
        let c0 = _mm256_loadu_si256(p as *const __m256i);
        let c1 = _mm256_loadu_si256(p.add(16) as *const __m256i);

        let skipped = try_skip_ignored_uniform_run(src, len, i, c0, c1, lut, lut_0);
        if skipped > 0 {
            i += skipped;
            continue;
        }

        for k in 0..32 {
            let val = *p.add(k) as usize;
            if *lut.add(val) == 0 {
                count += 1;
            }
        }
        i += 32;
    }

    while i < len {
        let val = *src.add(i) as usize;
        if *lut.add(val) == 0 {
            count += 1;
        }
        i += 1;
    }

    count
}

unsafe fn count_matching_shorts_scalar(src: *const u16, len: usize, lut: *const u8) -> usize {
    let mut count = 0usize;
    for i in 0..len {
        let val = *src.add(i) as usize;
        if *lut.add(val) == 0 {
            count += 1;
        }
    }
    count
}

/// Scans matching shorts and writes their indices (`base_index + i`) into `out_indices`.
/// Returns the number of indices written (up to `max_out`).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn scan_matching_shorts(
    src_ptr: *const u16,
    len: u64,
    base_index: u64,
    ignore_lut_ptr: *const u8,
    out_indices: *mut i32,
    max_out: u64,
) -> u64 {
    if src_ptr.is_null() || len == 0 || ignore_lut_ptr.is_null() || out_indices.is_null() || max_out == 0 {
        return 0;
    }

    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        return scan_matching_shorts_avx2(
            src_ptr,
            len as usize,
            base_index,
            ignore_lut_ptr,
            out_indices,
            max_out as usize,
        ) as u64;
    }

    scan_matching_shorts_scalar(
        src_ptr,
        len as usize,
        base_index,
        ignore_lut_ptr,
        out_indices,
        max_out as usize,
    ) as u64
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn scan_matching_shorts_avx2(
    src: *const u16,
    len: usize,
    base_index: u64,
    lut: *const u8,
    out: *mut i32,
    max_out: usize,
) -> usize {
    use core::arch::x86_64::*;

    let lut_0 = *lut;
    let mut i = 0;
    let mut written = 0usize;

    while i + 32 <= len && written < max_out {
        let p = src.add(i);
        let c0 = _mm256_loadu_si256(p as *const __m256i);
        let c1 = _mm256_loadu_si256(p.add(16) as *const __m256i);

        let skipped = try_skip_ignored_uniform_run(src, len, i, c0, c1, lut, lut_0);
        if skipped > 0 {
            i += skipped;
            continue;
        }

        for k in 0..32 {
            let val = *p.add(k) as usize;
            if *lut.add(val) == 0 && written < max_out {
                *out.add(written) = (base_index + (i + k) as u64) as i32;
                written += 1;
            }
        }
        i += 32;
    }

    while i < len && written < max_out {
        let val = *src.add(i) as usize;
        if *lut.add(val) == 0 {
            *out.add(written) = (base_index + i as u64) as i32;
            written += 1;
        }
        i += 1;
    }

    written
}

unsafe fn scan_matching_shorts_scalar(
    src: *const u16,
    len: usize,
    base_index: u64,
    lut: *const u8,
    out: *mut i32,
    max_out: usize,
) -> usize {
    let mut written = 0usize;
    for i in 0..len {
        if written >= max_out {
            break;
        }
        let val = *src.add(i) as usize;
        if *lut.add(val) == 0 {
            *out.add(written) = (base_index + i as u64) as i32;
            written += 1;
        }
    }
    written
}

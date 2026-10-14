/// Replaces all occurrences of `target` with `replacement` in-place using AVX2 SIMD.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn replace_shorts(
    data: *mut i16,
    len: u64,
    target: i16,
    replacement: i16,
) {
    if data.is_null() || len == 0 {
        return;
    }

    let slice = core::slice::from_raw_parts_mut(data, len as usize);

    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        replace_shorts_avx2(slice, target, replacement);
        return;
    }

    replace_shorts_fallback(slice, target, replacement);
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn replace_shorts_avx2(slice: &mut [i16], target: i16, replacement: i16) {
    use core::arch::x86_64::*;

    let target_vec = _mm256_set1_epi16(target);
    let repl_vec = _mm256_set1_epi16(replacement);
    let mut i = 0;
    let len = slice.len();
    let base_ptr = slice.as_mut_ptr();

    while i + 32 <= len {
        let ptr0 = base_ptr.add(i) as *mut __m256i;
        let ptr1 = base_ptr.add(i + 16) as *mut __m256i;

        let data0 = _mm256_loadu_si256(ptr0);
        let data1 = _mm256_loadu_si256(ptr1);

        let mask0 = _mm256_cmpeq_epi16(data0, target_vec);
        let mask1 = _mm256_cmpeq_epi16(data1, target_vec);

        if _mm256_testz_si256(mask0, mask0) == 0 {
            let result0 = _mm256_blendv_epi8(data0, repl_vec, mask0);
            _mm256_storeu_si256(ptr0, result0);
        }

        if _mm256_testz_si256(mask1, mask1) == 0 {
            let result1 = _mm256_blendv_epi8(data1, repl_vec, mask1);
            _mm256_storeu_si256(ptr1, result1);
        }

        i += 32;
    }

    while i < len {
        let element = slice.get_unchecked_mut(i);
        if *element == target {
            *element = replacement;
        }
        i += 1;
    }
}

unsafe fn replace_shorts_fallback(slice: &mut [i16], target: i16, replacement: i16) {
    for element in slice.iter_mut() {
        if *element == target {
            *element = replacement;
        }
    }
}

/// Fills all elements in `data` with `value` in-place using AVX2 SIMD.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fill_shorts(
    data: *mut i16,
    len: u64,
    value: i16,
) {
    if data.is_null() || len == 0 {
        return;
    }

    let slice = core::slice::from_raw_parts_mut(data, len as usize);

    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        fill_shorts_avx2(slice, value);
        return;
    }

    slice.fill(value);
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn fill_shorts_avx2(slice: &mut [i16], value: i16) {
    use core::arch::x86_64::*;

    let val_vec = _mm256_set1_epi16(value);
    let mut i = 0;
    let len = slice.len();

    while i + 16 <= len {
        let ptr = slice.as_mut_ptr().add(i) as *mut __m256i;
        _mm256_storeu_si256(ptr, val_vec);
        i += 16;
    }

    while i < len {
        *slice.get_unchecked_mut(i) = value;
        i += 1;
    }
}

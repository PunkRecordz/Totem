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

    let slice = unsafe { core::slice::from_raw_parts_mut(data, len as usize) };

    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        unsafe { replace_shorts_avx2(slice, target, replacement) };
        return;
    }

    replace_shorts_fallback(slice, target, replacement);
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
fn replace_shorts_avx2(slice: &mut [i16], target: i16, replacement: i16) {
    use crate::simd::{load_i16x16, store_i16x16};
    use core::arch::x86_64::*;

    let target_vec = _mm256_set1_epi16(target);
    let repl_vec = _mm256_set1_epi16(replacement);
    let (chunks, remainder) = slice.as_chunks_mut::<32>();

    for chunk in chunks {
        let (c0, rest) = chunk.split_first_chunk_mut::<16>().unwrap();
        let c1: &mut [i16; 16] = rest.try_into().unwrap();

        let data0 = load_i16x16(c0);
        let data1 = load_i16x16(c1);

        let mask0 = _mm256_cmpeq_epi16(data0, target_vec);
        let mask1 = _mm256_cmpeq_epi16(data1, target_vec);

        if _mm256_testz_si256(mask0, mask0) == 0 {
            let result0 = _mm256_blendv_epi8(data0, repl_vec, mask0);
            store_i16x16(c0, result0);
        }

        if _mm256_testz_si256(mask1, mask1) == 0 {
            let result1 = _mm256_blendv_epi8(data1, repl_vec, mask1);
            store_i16x16(c1, result1);
        }
    }

    replace_shorts_fallback(remainder, target, replacement);
}

fn replace_shorts_fallback(slice: &mut [i16], target: i16, replacement: i16) {
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

    let slice = unsafe { core::slice::from_raw_parts_mut(data, len as usize) };

    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        unsafe { fill_shorts_avx2(slice, value) };
        return;
    }

    slice.fill(value);
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
fn fill_shorts_avx2(slice: &mut [i16], value: i16) {
    use crate::simd::store_i16x16;
    use core::arch::x86_64::*;

    let val_vec = _mm256_set1_epi16(value);
    let (chunks, remainder) = slice.as_chunks_mut::<16>();

    for chunk in chunks {
        store_i16x16(chunk, val_vec);
    }

    remainder.fill(value);
}

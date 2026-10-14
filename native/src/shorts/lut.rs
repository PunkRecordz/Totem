/// Translates an array of 16-bit short values in-place using a 65,536-entry lookup table.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn translate_shorts_lut_avx2(
    data: *mut i16,
    len: usize,
    lut_slice: &[i16],
) -> usize {
    use core::arch::x86_64::*;

    let mut i = 0;

    // process 32 shorts (64 bytes = 1 full cpu cache line) per iteration
    while i + 32 <= len {
        let p = data.add(i);
        let c0 = _mm256_loadu_si256(p as *const __m256i);
        let c1 = _mm256_loadu_si256(p.add(16) as *const __m256i);

        let first_short = core::ptr::read(p) as u16 as usize;
        let splat = _mm256_set1_epi16(first_short as i16);
        let diff0 = _mm256_xor_si256(c0, splat);
        let diff1 = _mm256_xor_si256(c1, splat);
        let diff = _mm256_or_si256(diff0, diff1);

        // fast path: entire 64-byte line is uniform (all shorts equal to first_short)
        if _mm256_testz_si256(diff, diff) != 0 {
            let mapped = *lut_slice.get_unchecked(first_short);
            let mapped_differs = mapped != first_short as i16;
            let mapped_splat = if mapped_differs { _mm256_set1_epi16(mapped) } else { splat };

            if mapped_differs {
                _mm256_storeu_si256(p as *mut __m256i, mapped_splat);
                _mm256_storeu_si256(p.add(16) as *mut __m256i, mapped_splat);
            }
            i += 32;

            // run-length acceleration: consume subsequent identical uniform 32-short chunks
            while i + 32 <= len {
                let next_p = data.add(i);
                let nc0 = _mm256_loadu_si256(next_p as *const __m256i);
                let nc1 = _mm256_loadu_si256(next_p.add(16) as *const __m256i);
                let ndiff0 = _mm256_xor_si256(nc0, splat);
                let ndiff1 = _mm256_xor_si256(nc1, splat);
                let ndiff = _mm256_or_si256(ndiff0, ndiff1);
                if _mm256_testz_si256(ndiff, ndiff) != 0 {
                    if mapped_differs {
                        _mm256_storeu_si256(next_p as *mut __m256i, mapped_splat);
                        _mm256_storeu_si256(next_p.add(16) as *mut __m256i, mapped_splat);
                    }
                    i += 32;
                } else {
                    break;
                }
            }
            continue;
        }

        translate_16_shorts_chunk(p, c0, lut_slice);
        translate_16_shorts_chunk(p.add(16), c1, lut_slice);

        i += 32;
    }

    i
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
unsafe fn translate_16_shorts_chunk(
    p: *mut i16,
    chunk: core::arch::x86_64::__m256i,
    lut_slice: &[i16],
) {
    use core::arch::x86_64::*;

    let v0 = core::ptr::read(p) as u16 as usize;
    let splat0 = _mm256_set1_epi16(v0 as i16);
    let diff = _mm256_xor_si256(chunk, splat0);

    if _mm256_testz_si256(diff, diff) != 0 {
        let mapped0 = *lut_slice.get_unchecked(v0);
        if mapped0 != v0 as i16 {
            _mm256_storeu_si256(p as *mut __m256i, _mm256_set1_epi16(mapped0));
        }
        return;
    }

    let res = _mm256_setr_epi16(
        *lut_slice.get_unchecked(v0),
        *lut_slice.get_unchecked(core::ptr::read(p.add(1)) as u16 as usize),
        *lut_slice.get_unchecked(core::ptr::read(p.add(2)) as u16 as usize),
        *lut_slice.get_unchecked(core::ptr::read(p.add(3)) as u16 as usize),
        *lut_slice.get_unchecked(core::ptr::read(p.add(4)) as u16 as usize),
        *lut_slice.get_unchecked(core::ptr::read(p.add(5)) as u16 as usize),
        *lut_slice.get_unchecked(core::ptr::read(p.add(6)) as u16 as usize),
        *lut_slice.get_unchecked(core::ptr::read(p.add(7)) as u16 as usize),
        *lut_slice.get_unchecked(core::ptr::read(p.add(8)) as u16 as usize),
        *lut_slice.get_unchecked(core::ptr::read(p.add(9)) as u16 as usize),
        *lut_slice.get_unchecked(core::ptr::read(p.add(10)) as u16 as usize),
        *lut_slice.get_unchecked(core::ptr::read(p.add(11)) as u16 as usize),
        *lut_slice.get_unchecked(core::ptr::read(p.add(12)) as u16 as usize),
        *lut_slice.get_unchecked(core::ptr::read(p.add(13)) as u16 as usize),
        *lut_slice.get_unchecked(core::ptr::read(p.add(14)) as u16 as usize),
        *lut_slice.get_unchecked(core::ptr::read(p.add(15)) as u16 as usize),
    );

    let eq = _mm256_cmpeq_epi16(chunk, res);
    if _mm256_movemask_epi8(eq) as u32 != 0xFFFFFFFF {
        _mm256_storeu_si256(p as *mut __m256i, res);
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn translate_shorts_lut(
    data: *mut i16,
    len: u64,
    lut: *const i16,
) {
    if data.is_null() || len == 0 || lut.is_null() {
        return;
    }

    let slice = core::slice::from_raw_parts_mut(data, len as usize);
    let lut_slice = core::slice::from_raw_parts(lut, 65536);

    #[cfg(target_arch = "x86_64")]
    let mut i = if is_x86_feature_detected!("avx2") {
        unsafe { translate_shorts_lut_avx2(data, len as usize, lut_slice) }
    } else {
        0
    };

    #[cfg(not(target_arch = "x86_64"))]
    let mut i = 0usize;

    // clean up remaining elements with scalar write-bypass
    while i < len as usize {
        let p = slice.as_mut_ptr().add(i);
        let v = core::ptr::read(p) as u16 as usize;
        let r = *lut_slice.get_unchecked(v);
        if r != v as i16 {
            core::ptr::write(p, r);
        }
        i += 1;
    }
}

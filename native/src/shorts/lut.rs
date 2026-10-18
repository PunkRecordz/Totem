/// Translates an array of 16-bit short values in-place using a 65,536-entry lookup table.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
fn translate_shorts_lut_avx2(slice: &mut [i16], lut_table: &[i16; 65536]) -> usize {
    use crate::simd::{load_i16x16, store_i16x16};
    use core::arch::x86_64::*;

    let (chunks, _) = slice.as_chunks_mut::<32>();
    let chunk_count = chunks.len();
    let mut idx = 0;

    // process 32 shorts (64 bytes = 1 full cpu cache line) per iteration
    while idx < chunk_count {
        let first_short = chunks[idx][0] as u16 as usize;
        let (first_half, rest) = chunks[idx].split_first_chunk_mut::<16>().unwrap();
        let second_half: &mut [i16; 16] = rest.try_into().unwrap();

        let c0 = load_i16x16(first_half);
        let c1 = load_i16x16(second_half);

        let splat = _mm256_set1_epi16(first_short as i16);
        let diff0 = _mm256_xor_si256(c0, splat);
        let diff1 = _mm256_xor_si256(c1, splat);
        let diff = _mm256_or_si256(diff0, diff1);

        // fast path: entire 64-byte line is uniform (all shorts equal to first_short)
        if _mm256_testz_si256(diff, diff) != 0 {
            let mapped = lut_table[first_short];
            let mapped_differs = mapped != first_short as i16;
            let mapped_splat = if mapped_differs {
                _mm256_set1_epi16(mapped)
            } else {
                splat
            };

            if mapped_differs {
                store_i16x16(first_half, mapped_splat);
                store_i16x16(second_half, mapped_splat);
            }
            idx += 1;

            // run-length acceleration: consume subsequent identical uniform 32-short chunks
            while idx < chunk_count {
                let (nh0, nrest) = chunks[idx].split_first_chunk_mut::<16>().unwrap();
                let nh1: &mut [i16; 16] = nrest.try_into().unwrap();
                let nc0 = load_i16x16(nh0);
                let nc1 = load_i16x16(nh1);
                let ndiff0 = _mm256_xor_si256(nc0, splat);
                let ndiff1 = _mm256_xor_si256(nc1, splat);
                let ndiff = _mm256_or_si256(ndiff0, ndiff1);
                if _mm256_testz_si256(ndiff, ndiff) != 0 {
                    if mapped_differs {
                        store_i16x16(nh0, mapped_splat);
                        store_i16x16(nh1, mapped_splat);
                    }
                    idx += 1;
                } else {
                    break;
                }
            }
            continue;
        }

        translate_16_shorts_chunk(first_half, c0, lut_table);
        translate_16_shorts_chunk(second_half, c1, lut_table);

        idx += 1;
    }

    chunk_count * 32
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
fn translate_16_shorts_chunk(
    chunk_slice: &mut [i16; 16],
    chunk: core::arch::x86_64::__m256i,
    lut_table: &[i16; 65536],
) {
    use crate::simd::store_i16x16;
    use core::arch::x86_64::*;

    let v0 = chunk_slice[0] as u16 as usize;
    let splat0 = _mm256_set1_epi16(v0 as i16);
    let diff = _mm256_xor_si256(chunk, splat0);

    if _mm256_testz_si256(diff, diff) != 0 {
        let mapped0 = lut_table[v0];
        if mapped0 != v0 as i16 {
            store_i16x16(chunk_slice, _mm256_set1_epi16(mapped0));
        }
        return;
    }

    let res = _mm256_setr_epi16(
        lut_table[v0],
        lut_table[chunk_slice[1] as u16 as usize],
        lut_table[chunk_slice[2] as u16 as usize],
        lut_table[chunk_slice[3] as u16 as usize],
        lut_table[chunk_slice[4] as u16 as usize],
        lut_table[chunk_slice[5] as u16 as usize],
        lut_table[chunk_slice[6] as u16 as usize],
        lut_table[chunk_slice[7] as u16 as usize],
        lut_table[chunk_slice[8] as u16 as usize],
        lut_table[chunk_slice[9] as u16 as usize],
        lut_table[chunk_slice[10] as u16 as usize],
        lut_table[chunk_slice[11] as u16 as usize],
        lut_table[chunk_slice[12] as u16 as usize],
        lut_table[chunk_slice[13] as u16 as usize],
        lut_table[chunk_slice[14] as u16 as usize],
        lut_table[chunk_slice[15] as u16 as usize],
    );

    let eq = _mm256_cmpeq_epi16(chunk, res);
    if _mm256_movemask_epi8(eq) as u32 != 0xFFFFFFFF {
        store_i16x16(chunk_slice, res);
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

    let slice = unsafe { core::slice::from_raw_parts_mut(data, len as usize) };
    let lut_table = unsafe { &*(lut as *const [i16; 65536]) };

    #[cfg(target_arch = "x86_64")]
    let processed = if is_x86_feature_detected!("avx2") {
        unsafe { translate_shorts_lut_avx2(slice, lut_table) }
    } else {
        0
    };

    #[cfg(not(target_arch = "x86_64"))]
    let processed = 0usize;

    // clean up remaining elements with scalar write-bypass
    for element in &mut slice[processed..] {
        let current = *element;
        let mapped = lut_table[current as u16 as usize];
        if mapped != current {
            *element = mapped;
        }
    }
}

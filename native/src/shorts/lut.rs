#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
fn translate_shorts_lut_avx2(slice: &mut [i16], lookup_table: &[i16; 65536]) -> usize {
    use crate::simd::{load_i16x16, store_i16x16};
    use core::arch::x86_64::*;

    let (chunks, _) = slice.as_chunks_mut::<32>();
    let chunk_count = chunks.len();
    let mut chunk_index = 0;

    // process 32 shorts (64 bytes = 1 full cpu cache line) per iteration
    while chunk_index < chunk_count {
        let first_short = chunks[chunk_index][0] as u16 as usize;
        let (first_half, rest) = chunks[chunk_index].split_first_chunk_mut::<16>().unwrap();
        let second_half: &mut [i16; 16] = rest.try_into().unwrap();

        let first_vector = load_i16x16(first_half);
        let second_vector = load_i16x16(second_half);

        let splat = _mm256_set1_epi16(first_short as i16);
        let diff_first = _mm256_xor_si256(first_vector, splat);
        let diff_second = _mm256_xor_si256(second_vector, splat);
        let combined_diff = _mm256_or_si256(diff_first, diff_second);

        // fast path: entire 64-byte line is uniform
        if _mm256_testz_si256(combined_diff, combined_diff) != 0 {
            let mapped = lookup_table[first_short];
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
            chunk_index += 1;

            // run-length acceleration: consume subsequent identical uniform 32-short chunks
            while chunk_index < chunk_count {
                let (next_first, next_rest) = chunks[chunk_index].split_first_chunk_mut::<16>().unwrap();
                let next_second: &mut [i16; 16] = next_rest.try_into().unwrap();
                let next_vector_first = load_i16x16(next_first);
                let next_vector_second = load_i16x16(next_second);
                let next_diff_first = _mm256_xor_si256(next_vector_first, splat);
                let next_diff_second = _mm256_xor_si256(next_vector_second, splat);
                let next_combined_diff = _mm256_or_si256(next_diff_first, next_diff_second);
                if _mm256_testz_si256(next_combined_diff, next_combined_diff) != 0 {
                    if mapped_differs {
                        store_i16x16(next_first, mapped_splat);
                        store_i16x16(next_second, mapped_splat);
                    }
                    chunk_index += 1;
                } else {
                    break;
                }
            }
            continue;
        }

        translate_16_shorts_chunk(first_half, first_vector, lookup_table);
        translate_16_shorts_chunk(second_half, second_vector, lookup_table);

        chunk_index += 1;
    }

    chunk_count * 32
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
fn translate_16_shorts_chunk(
    chunk_slice: &mut [i16; 16],
    chunk: core::arch::x86_64::__m256i,
    lookup_table: &[i16; 65536],
) {
    use crate::simd::store_i16x16;
    use core::arch::x86_64::*;

    let first_value = chunk_slice[0] as u16 as usize;
    let first_splat = _mm256_set1_epi16(first_value as i16);
    let diff = _mm256_xor_si256(chunk, first_splat);

    if _mm256_testz_si256(diff, diff) != 0 {
        let mapped_first = lookup_table[first_value];
        if mapped_first != first_value as i16 {
            store_i16x16(chunk_slice, _mm256_set1_epi16(mapped_first));
        }
        return;
    }

    let translated = _mm256_setr_epi16(
        lookup_table[first_value],
        lookup_table[chunk_slice[1] as u16 as usize],
        lookup_table[chunk_slice[2] as u16 as usize],
        lookup_table[chunk_slice[3] as u16 as usize],
        lookup_table[chunk_slice[4] as u16 as usize],
        lookup_table[chunk_slice[5] as u16 as usize],
        lookup_table[chunk_slice[6] as u16 as usize],
        lookup_table[chunk_slice[7] as u16 as usize],
        lookup_table[chunk_slice[8] as u16 as usize],
        lookup_table[chunk_slice[9] as u16 as usize],
        lookup_table[chunk_slice[10] as u16 as usize],
        lookup_table[chunk_slice[11] as u16 as usize],
        lookup_table[chunk_slice[12] as u16 as usize],
        lookup_table[chunk_slice[13] as u16 as usize],
        lookup_table[chunk_slice[14] as u16 as usize],
        lookup_table[chunk_slice[15] as u16 as usize],
    );

    let equal_mask = _mm256_cmpeq_epi16(chunk, translated);
    if _mm256_movemask_epi8(equal_mask) as u32 != 0xFFFFFFFF {
        store_i16x16(chunk_slice, translated);
    }
}

pub(crate) fn translate_shorts_lut_slice(slice: &mut [i16], lookup_table: &[i16; 65536]) {
    #[cfg(target_arch = "x86_64")]
    let processed = if is_x86_feature_detected!("avx2") {
        unsafe { translate_shorts_lut_avx2(slice, lookup_table) }
    } else {
        0
    };

    #[cfg(not(target_arch = "x86_64"))]
    let processed = 0usize;

    // clean up remaining elements with scalar write-bypass
    for element in &mut slice[processed..] {
        let current = *element;
        let mapped = lookup_table[current as u16 as usize];
        if mapped != current {
            *element = mapped;
        }
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

    let count = len as usize;
    if !data.is_aligned() || !lut.is_aligned() {
        for index in 0..count {
            let slot = unsafe { data.add(index) };
            let current = unsafe { slot.read_unaligned() };
            let mapped = unsafe { lut.add(current as u16 as usize).read_unaligned() };
            if mapped != current {
                unsafe { slot.write_unaligned(mapped) };
            }
        }
        return;
    }

    let slice = unsafe { core::slice::from_raw_parts_mut(data, count) };
    let lookup_table = unsafe { &*(lut as *const [i16; 65536]) };
    translate_shorts_lut_slice(slice, lookup_table);
}

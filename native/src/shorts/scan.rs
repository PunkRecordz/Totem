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

    let src = unsafe { core::slice::from_raw_parts(src_ptr, len as usize) };
    let lut = unsafe { &*(ignore_lut_ptr as *const [u8; 65536]) };

    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        return unsafe { count_matching_shorts_avx2(src, lut) } as u64;
    }

    count_matching_shorts_scalar(src, lut) as u64
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
fn try_skip_ignored_uniform_run(
    chunks: &[[u16; 32]],
    chunk_idx: usize,
    c0: core::arch::x86_64::__m256i,
    c1: core::arch::x86_64::__m256i,
    lut: &[u8; 65536],
    lut_0: u8,
) -> usize {
    use crate::simd::load_u16x16;
    use core::arch::x86_64::*;

    let or_combined = _mm256_or_si256(c0, c1);
    if lut_0 != 0 && _mm256_testz_si256(or_combined, or_combined) != 0 {
        let mut skipped_chunks = 1usize;
        for next_chunk in &chunks[chunk_idx + 1..] {
            let (nh0, nrest) = next_chunk.split_first_chunk::<16>().unwrap();
            let nh1: &[u16; 16] = nrest.try_into().unwrap();
            let nc0 = load_u16x16(nh0);
            let nc1 = load_u16x16(nh1);
            let next_or = _mm256_or_si256(nc0, nc1);
            if _mm256_testz_si256(next_or, next_or) == 0 {
                break;
            }
            skipped_chunks += 1;
        }
        return skipped_chunks;
    }

    let first_short = chunks[chunk_idx][0];
    if lut[usize::from(first_short)] != 0 {
        let v_first = _mm256_set1_epi16(first_short as i16);
        let cmp0 = _mm256_cmpeq_epi16(c0, v_first);
        let cmp1 = _mm256_cmpeq_epi16(c1, v_first);
        let cmp_both = _mm256_and_si256(cmp0, cmp1);
        if _mm256_movemask_epi8(cmp_both) as u32 == 0xFFFF_FFFF {
            let mut skipped_chunks = 1usize;
            for next_chunk in &chunks[chunk_idx + 1..] {
                let (nh0, nrest) = next_chunk.split_first_chunk::<16>().unwrap();
                let nh1: &[u16; 16] = nrest.try_into().unwrap();
                let nc0 = load_u16x16(nh0);
                let nc1 = load_u16x16(nh1);
                let c0 = _mm256_cmpeq_epi16(nc0, v_first);
                let c1 = _mm256_cmpeq_epi16(nc1, v_first);
                if _mm256_movemask_epi8(_mm256_and_si256(c0, c1)) as u32 != 0xFFFF_FFFF {
                    break;
                }
                skipped_chunks += 1;
            }
            return skipped_chunks;
        }
    }

    0
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
fn count_matching_shorts_avx2(src: &[u16], lut: &[u8; 65536]) -> usize {
    use crate::simd::load_u16x16;

    let (chunks, remainder) = src.as_chunks::<32>();
    let lut_0 = lut[0];
    let mut chunk_idx = 0;
    let mut count = 0usize;

    while chunk_idx < chunks.len() {
        let chunk = &chunks[chunk_idx];
        let (h0, rest) = chunk.split_first_chunk::<16>().unwrap();
        let h1: &[u16; 16] = rest.try_into().unwrap();
        let c0 = load_u16x16(h0);
        let c1 = load_u16x16(h1);

        let skipped = try_skip_ignored_uniform_run(chunks, chunk_idx, c0, c1, lut, lut_0);
        if skipped > 0 {
            chunk_idx += skipped;
            continue;
        }

        for &val in chunk {
            if lut[usize::from(val)] == 0 {
                count += 1;
            }
        }
        chunk_idx += 1;
    }

    count + count_matching_shorts_scalar(remainder, lut)
}

fn count_matching_shorts_scalar(src: &[u16], lut: &[u8; 65536]) -> usize {
    let mut count = 0usize;
    for &val in src {
        if lut[usize::from(val)] == 0 {
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
    if src_ptr.is_null()
        || len == 0
        || ignore_lut_ptr.is_null()
        || out_indices.is_null()
        || max_out == 0
    {
        return 0;
    }

    let src = unsafe { core::slice::from_raw_parts(src_ptr, len as usize) };
    let lut = unsafe { &*(ignore_lut_ptr as *const [u8; 65536]) };
    let out = unsafe { core::slice::from_raw_parts_mut(out_indices, max_out as usize) };

    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        return unsafe { scan_matching_shorts_avx2(src, base_index, lut, out) } as u64;
    }

    scan_matching_shorts_scalar(src, base_index, lut, out) as u64
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
fn scan_matching_shorts_avx2(
    src: &[u16],
    base_index: u64,
    lut: &[u8; 65536],
    out: &mut [i32],
) -> usize {
    use crate::simd::load_u16x16;

    let (chunks, remainder) = src.as_chunks::<32>();
    let max_out = out.len();
    let lut_0 = lut[0];
    let mut chunk_idx = 0;
    let mut written = 0usize;

    while chunk_idx < chunks.len() && written < max_out {
        let chunk = &chunks[chunk_idx];
        let (h0, rest) = chunk.split_first_chunk::<16>().unwrap();
        let h1: &[u16; 16] = rest.try_into().unwrap();
        let c0 = load_u16x16(h0);
        let c1 = load_u16x16(h1);

        let skipped = try_skip_ignored_uniform_run(chunks, chunk_idx, c0, c1, lut, lut_0);
        if skipped > 0 {
            chunk_idx += skipped;
            continue;
        }

        let i = chunk_idx * 32;
        for (k, &val) in chunk.iter().enumerate() {
            if lut[usize::from(val)] == 0 {
                if let Some(slot) = out.get_mut(written) {
                    *slot = (base_index + (i + k) as u64) as i32;
                    written += 1;
                } else {
                    break;
                }
            }
        }
        chunk_idx += 1;
    }

    if written < max_out && !remainder.is_empty() {
        let tail_start = chunks.len() * 32;
        written += scan_matching_shorts_scalar(
            remainder,
            base_index + tail_start as u64,
            lut,
            &mut out[written..],
        );
    }

    written
}

fn scan_matching_shorts_scalar(
    src: &[u16],
    base_index: u64,
    lut: &[u8; 65536],
    out: &mut [i32],
) -> usize {
    let mut written = 0usize;
    let max_out = out.len();
    for (i, &val) in src.iter().enumerate() {
        if written >= max_out {
            break;
        }
        if lut[usize::from(val)] == 0 {
            out[written] = (base_index + i as u64) as i32;
            written += 1;
        }
    }
    written
}

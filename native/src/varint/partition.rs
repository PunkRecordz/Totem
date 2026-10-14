/// Counts the number of terminal VarInt bytes (MSB == 0) in the byte slice using AVX2.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn count_varint_terminals_avx2(src: *const u8, len: usize) -> usize {
    use core::arch::x86_64::*;

    let mut offset = 0;
    let mut count = 0usize;

    while offset + 128 <= len {
        let c0 = _mm256_loadu_si256(src.add(offset) as *const __m256i);
        let c1 = _mm256_loadu_si256(src.add(offset + 32) as *const __m256i);
        let c2 = _mm256_loadu_si256(src.add(offset + 64) as *const __m256i);
        let c3 = _mm256_loadu_si256(src.add(offset + 96) as *const __m256i);

        let m0 = _mm256_movemask_epi8(c0) as u32;
        let m1 = _mm256_movemask_epi8(c1) as u32;
        let m2 = _mm256_movemask_epi8(c2) as u32;
        let m3 = _mm256_movemask_epi8(c3) as u32;

        let ones = (m0.count_ones() + m1.count_ones() + m2.count_ones() + m3.count_ones()) as usize;
        count += 128 - ones;
        offset += 128;
    }

    while offset + 64 <= len {
        let c0 = _mm256_loadu_si256(src.add(offset) as *const __m256i);
        let c1 = _mm256_loadu_si256(src.add(offset + 32) as *const __m256i);

        let m0 = _mm256_movemask_epi8(c0) as u32;
        let m1 = _mm256_movemask_epi8(c1) as u32;

        count += 64 - (m0.count_ones() + m1.count_ones()) as usize;
        offset += 64;
    }

    while offset + 32 <= len {
        let chunk = _mm256_loadu_si256(src.add(offset) as *const __m256i);
        let mask = _mm256_movemask_epi8(chunk) as u32;
        count += 32 - mask.count_ones() as usize;
        offset += 32;
    }

    while offset < len {
        if (*src.add(offset) & 0x80) == 0 {
            count += 1;
        }
        offset += 1;
    }

    count
}

/// Fallback terminal counter using 64-bit SWAR.
#[inline(always)]
unsafe fn count_varint_terminals_swar(src: *const u8, len: usize) -> usize {
    let mut offset = 0;
    let mut count = 0usize;

    while offset + 8 <= len {
        let pointer = src.add(offset) as *const u64;
        let packed = u64::from_le(core::ptr::read_unaligned(pointer));
        let msbs = packed & 0x8080808080808080;
        let msb_count = (msbs >> 7).wrapping_mul(0x0101010101010101) >> 56;
        count += 8 - (msb_count as usize & 0xFF);
        offset += 8;
    }

    while offset < len {
        if (*src.add(offset) & 0x80) == 0 {
            count += 1;
        }
        offset += 1;
    }

    count
}

/// Counts how many terminal VarInt bytes (bytes with MSB == 0) exist in `src_ptr[0..src_len]`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn count_varint_terminals(
    src_ptr: *const u8,
    src_len: u64,
) -> u64 {
    let len = src_len as usize;

    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        return unsafe { count_varint_terminals_avx2(src_ptr, len) } as u64;
    }

    unsafe { count_varint_terminals_swar(src_ptr, len) as u64 }
}

/// Partitions a VarInt stream into `p` chunks along exact terminal boundaries (MSB = 0).
/// Writes `p + 1` entries into `out_byte_starts`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn find_varint_boundaries(
    src_ptr: *const u8,
    src_len: u64,
    partition_count: u32,
    out_byte_starts: *mut u64,
) {
    let p = partition_count as usize;
    let len = src_len as usize;

    if p == 0 || len == 0 {
        return;
    }

    let target_chunk = len / p;
    *out_byte_starts = 0;
    *out_byte_starts.add(p) = len as u64;

    for i in 1..p {
        let mut b = core::cmp::min(len, i * target_chunk);
        while b < len && (*src_ptr.add(b - 1) & 0x80) != 0 {
            b += 1;
        }
        *out_byte_starts.add(i) = b as u64;
    }
}

/// Identifies VarInt terminal boundaries and computes partition short offsets in a single AVX2 pass.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn partition_varint_stream(
    src_ptr: *const u8,
    src_len: u64,
    partition_count: u32,
    out_byte_starts: *mut u64,
    out_short_offsets: *mut u32,
    out_short_counts: *mut u32,
) -> u64 {
    let p = partition_count as usize;
    let len = src_len as usize;

    if p == 0 || len == 0 {
        return 0;
    }

    let target_chunk = len / p;
    let mut byte_starts = [0usize; 65];
    let starts_slice = if p <= 64 {
        &mut byte_starts[..=p]
    } else {
        let mut vec = vec![0usize; p + 1];
        let vec_ptr = vec.as_mut_ptr();
        core::mem::forget(vec);
        core::slice::from_raw_parts_mut(vec_ptr, p + 1)
    };

    starts_slice[0] = 0;
    starts_slice[p] = len;

    for i in 1..p {
        let mut b = core::cmp::min(len, i * target_chunk);
        while b < len && (*src_ptr.add(b - 1) & 0x80) != 0 {
            b += 1;
        }
        starts_slice[i] = b;
    }

    let mut running_short_offset = 0u32;

    for i in 0..p {
        let start = starts_slice[i];
        let end = starts_slice[i + 1];
        let chunk_len = end - start;

        *out_byte_starts.add(i) = start as u64;

        let terminals = if chunk_len > 0 {
            count_varint_terminals(src_ptr.add(start), chunk_len as u64) as u32
        } else {
            0u32
        };

        *out_short_offsets.add(i) = running_short_offset;
        *out_short_counts.add(i) = terminals;
        running_short_offset += terminals;
    }

    *out_byte_starts.add(p) = len as u64;

    if p > 64 {
        drop(Vec::from_raw_parts(starts_slice.as_mut_ptr(), p + 1, p + 1));
    }

    running_short_offset as u64
}

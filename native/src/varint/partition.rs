/// Counts the number of terminal VarInt bytes (MSB == 0) in the byte slice using AVX2.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
fn count_varint_terminals_avx2(src: &[u8]) -> usize {
    use crate::simd::load_u8x32;
    use core::arch::x86_64::*;

    let mut count = 0usize;
    let (blocks_128, after_128) = src.as_chunks::<128>();

    for block in blocks_128 {
        let (b0, r1) = block.split_first_chunk::<32>().unwrap();
        let (b1, r2) = r1.split_first_chunk::<32>().unwrap();
        let (b2, r3) = r2.split_first_chunk::<32>().unwrap();
        let b3: &[u8; 32] = r3.try_into().unwrap();

        let c0 = load_u8x32(b0);
        let c1 = load_u8x32(b1);
        let c2 = load_u8x32(b2);
        let c3 = load_u8x32(b3);

        let m0 = _mm256_movemask_epi8(c0) as u32;
        let m1 = _mm256_movemask_epi8(c1) as u32;
        let m2 = _mm256_movemask_epi8(c2) as u32;
        let m3 = _mm256_movemask_epi8(c3) as u32;

        let ones = (m0.count_ones() + m1.count_ones() + m2.count_ones() + m3.count_ones()) as usize;
        count += 128 - ones;
    }

    let (blocks_32, tail) = after_128.as_chunks::<32>();
    for chunk in blocks_32 {
        let vec = load_u8x32(chunk);
        let mask = _mm256_movemask_epi8(vec) as u32;
        count += 32 - mask.count_ones() as usize;
    }

    for &byte in tail {
        if (byte & 0x80) == 0 {
            count += 1;
        }
    }

    count
}

/// Fallback terminal counter using 64-bit SWAR.
#[inline(always)]
fn count_varint_terminals_swar(src: &[u8]) -> usize {
    let mut count = 0usize;
    let (chunks, remainder) = src.as_chunks::<8>();

    for chunk in chunks {
        let packed = u64::from_le_bytes(*chunk);
        let msbs = packed & 0x8080808080808080;
        let msb_count = (msbs >> 7).wrapping_mul(0x0101010101010101) >> 56;
        count += 8 - (msb_count as usize & 0xFF);
    }

    for &byte in remainder {
        if (byte & 0x80) == 0 {
            count += 1;
        }
    }

    count
}

#[inline(always)]
fn count_varint_terminals_slice(src: &[u8]) -> usize {
    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        return unsafe { count_varint_terminals_avx2(src) };
    }

    count_varint_terminals_swar(src)
}

/// Counts how many terminal VarInt bytes (bytes with MSB == 0) exist in `src_ptr[0..src_len]`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn count_varint_terminals(
    src_ptr: *const u8,
    src_len: u64,
) -> u64 {
    if src_ptr.is_null() || src_len == 0 {
        return 0;
    }

    let src = unsafe { core::slice::from_raw_parts(src_ptr, src_len as usize) };
    count_varint_terminals_slice(src) as u64
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

    if src_ptr.is_null() || out_byte_starts.is_null() || p == 0 || len == 0 {
        return;
    }

    let src = unsafe { core::slice::from_raw_parts(src_ptr, len) };
    let out = unsafe { core::slice::from_raw_parts_mut(out_byte_starts, p + 1) };

    let target_chunk = len / p;
    out[0] = 0;
    out[p] = len as u64;

    for i in 1..p {
        let mut b = core::cmp::min(len, i * target_chunk);
        while b < len && (src[b - 1] & 0x80) != 0 {
            b += 1;
        }
        out[i] = b as u64;
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

    if src_ptr.is_null()
        || out_byte_starts.is_null()
        || out_short_offsets.is_null()
        || out_short_counts.is_null()
        || p == 0
        || len == 0
    {
        return 0;
    }

    let src = unsafe { core::slice::from_raw_parts(src_ptr, len) };
    let byte_starts_out = unsafe { core::slice::from_raw_parts_mut(out_byte_starts, p + 1) };
    let short_offsets_out = unsafe { core::slice::from_raw_parts_mut(out_short_offsets, p) };
    let short_counts_out = unsafe { core::slice::from_raw_parts_mut(out_short_counts, p) };

    partition_varint_stream_slice(
        src,
        p,
        byte_starts_out,
        short_offsets_out,
        short_counts_out,
    )
}

fn partition_varint_stream_slice(
    src: &[u8],
    p: usize,
    out_byte_starts: &mut [u64],
    out_short_offsets: &mut [u32],
    out_short_counts: &mut [u32],
) -> u64 {
    let len = src.len();
    let target_chunk = len / p;
    let mut stack_starts = [0usize; 65];
    let mut heap_starts = Vec::new();
    let starts_slice: &mut [usize] = if p <= 64 {
        &mut stack_starts[..=p]
    } else {
        heap_starts.resize(p + 1, 0);
        &mut heap_starts
    };

    starts_slice[0] = 0;
    starts_slice[p] = len;

    for i in 1..p {
        let mut b = core::cmp::min(len, i * target_chunk);
        while b < len && (src[b - 1] & 0x80) != 0 {
            b += 1;
        }
        starts_slice[i] = b;
    }

    let mut running_short_offset = 0u32;

    for i in 0..p {
        let start = starts_slice[i];
        let end = starts_slice[i + 1];

        out_byte_starts[i] = start as u64;

        let terminals = if end > start {
            count_varint_terminals_slice(&src[start..end]) as u32
        } else {
            0u32
        };

        out_short_offsets[i] = running_short_offset;
        out_short_counts[i] = terminals;
        running_short_offset += terminals;
    }

    out_byte_starts[p] = len as u64;
    running_short_offset as u64
}

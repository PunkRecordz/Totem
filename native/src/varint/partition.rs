#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
fn count_varint_terminals_avx2(source: &[u8]) -> usize {
    use crate::simd::load_u8x32;
    use core::arch::x86_64::*;

    let mut count = 0usize;
    let (blocks_128, after_128) = source.as_chunks::<128>();

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
        let vector = load_u8x32(chunk);
        let mask = _mm256_movemask_epi8(vector) as u32;
        count += 32 - mask.count_ones() as usize;
    }

    for &byte in tail {
        if (byte & 0x80) == 0 {
            count += 1;
        }
    }

    count
}

#[inline(always)]
fn count_varint_terminals_swar(source: &[u8]) -> usize {
    let mut count = 0usize;
    let (chunks, remainder) = source.as_chunks::<8>();

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
pub(crate) fn count_varint_terminals_slice(source: &[u8]) -> usize {
    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        return unsafe { count_varint_terminals_avx2(source) };
    }

    count_varint_terminals_swar(source)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn count_varint_terminals(
    src_ptr: *const u8,
    src_len: u64,
) -> u64 {
    if src_ptr.is_null() || src_len == 0 {
        return 0;
    }

    let source = unsafe { core::slice::from_raw_parts(src_ptr, src_len as usize) };
    count_varint_terminals_slice(source) as u64
}

pub(crate) fn find_varint_boundaries_slice(
    source: &[u8],
    partition_count: usize,
    out_byte_starts: &mut [u64],
) {
    let length = source.len();
    if partition_count == 0 || length == 0 || out_byte_starts.len() <= partition_count {
        return;
    }

    let target_chunk = length / partition_count;
    out_byte_starts[0] = 0;
    out_byte_starts[partition_count] = length as u64;

    for index in 1..partition_count {
        let mut boundary = core::cmp::min(length, index * target_chunk);
        while boundary < length && (source[boundary - 1] & 0x80) != 0 {
            boundary += 1;
        }
        out_byte_starts[index] = boundary as u64;
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn find_varint_boundaries(
    src_ptr: *const u8,
    src_len: u64,
    partition_count: u32,
    out_byte_starts: *mut u64,
) {
    let partitions = partition_count as usize;
    let length = src_len as usize;

    if src_ptr.is_null() || out_byte_starts.is_null() || partitions == 0 || length == 0 {
        return;
    }

    let source = unsafe { core::slice::from_raw_parts(src_ptr, length) };
    if !out_byte_starts.is_aligned() {
        let mut aligned_starts = vec![0u64; partitions + 1];
        find_varint_boundaries_slice(source, partitions, &mut aligned_starts);
        unsafe {
            core::ptr::copy_nonoverlapping(
                aligned_starts.as_ptr() as *const u8,
                out_byte_starts as *mut u8,
                (partitions + 1) * core::mem::size_of::<u64>(),
            );
        }
        return;
    }

    let output = unsafe { core::slice::from_raw_parts_mut(out_byte_starts, partitions + 1) };
    find_varint_boundaries_slice(source, partitions, output);
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn partition_varint_stream(
    src_ptr: *const u8,
    src_len: u64,
    partition_count: u32,
    out_byte_starts: *mut u64,
    out_short_offsets: *mut u32,
    out_short_counts: *mut u32,
) -> u64 {
    let partitions = partition_count as usize;
    let length = src_len as usize;

    if src_ptr.is_null()
        || out_byte_starts.is_null()
        || out_short_offsets.is_null()
        || out_short_counts.is_null()
        || partitions == 0
        || length == 0
    {
        return 0;
    }

    let source = unsafe { core::slice::from_raw_parts(src_ptr, length) };
    if !out_byte_starts.is_aligned()
        || !out_short_offsets.is_aligned()
        || !out_short_counts.is_aligned()
    {
        let mut aligned_starts = vec![0u64; partitions + 1];
        let mut aligned_offsets = vec![0u32; partitions];
        let mut aligned_counts = vec![0u32; partitions];
        let total = partition_varint_stream_slice(
            source,
            partitions,
            &mut aligned_starts,
            &mut aligned_offsets,
            &mut aligned_counts,
        );
        unsafe {
            core::ptr::copy_nonoverlapping(
                aligned_starts.as_ptr() as *const u8,
                out_byte_starts as *mut u8,
                (partitions + 1) * core::mem::size_of::<u64>(),
            );
            core::ptr::copy_nonoverlapping(
                aligned_offsets.as_ptr() as *const u8,
                out_short_offsets as *mut u8,
                partitions * core::mem::size_of::<u32>(),
            );
            core::ptr::copy_nonoverlapping(
                aligned_counts.as_ptr() as *const u8,
                out_short_counts as *mut u8,
                partitions * core::mem::size_of::<u32>(),
            );
        }
        return total;
    }

    let byte_starts_out = unsafe { core::slice::from_raw_parts_mut(out_byte_starts, partitions + 1) };
    let short_offsets_out = unsafe { core::slice::from_raw_parts_mut(out_short_offsets, partitions) };
    let short_counts_out = unsafe { core::slice::from_raw_parts_mut(out_short_counts, partitions) };

    partition_varint_stream_slice(
        source,
        partitions,
        byte_starts_out,
        short_offsets_out,
        short_counts_out,
    )
}

fn partition_varint_stream_slice(
    source: &[u8],
    partitions: usize,
    out_byte_starts: &mut [u64],
    out_short_offsets: &mut [u32],
    out_short_counts: &mut [u32],
) -> u64 {
    find_varint_boundaries_slice(source, partitions, out_byte_starts);
    let mut running_short_offset = 0u32;

    for index in 0..partitions {
        let start = out_byte_starts[index] as usize;
        let end = out_byte_starts[index + 1] as usize;

        let terminals = if end > start {
            count_varint_terminals_slice(&source[start..end]) as u32
        } else {
            0u32
        };

        out_short_offsets[index] = running_short_offset;
        out_short_counts[index] = terminals;
        running_short_offset += terminals;
    }

    running_short_offset as u64
}

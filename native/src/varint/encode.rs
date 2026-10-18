use super::encode_swar::{
    encode_parallel_4, encode_parallel_4_two_bytes, encode_single, encode_single_branchless,
};

/// AVX2 encoder processing up to 64 elements at once, with branchless 16-wide encoding for mixed 1/2-byte streams.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
fn encode_shorts_varint_avx2(
    source: &[u16],
    destination: &mut [u8],
    mut source_offset: usize,
    mut destination_offset: usize,
) -> (usize, usize) {
    use crate::simd::{load_u16x16, store_u8x16, store_u8x32};
    use core::arch::x86_64::*;

    let mask_ge_128 = _mm256_set1_epi16(0xFF80u16 as i16);
    let mask_ge_16384 = _mm256_set1_epi16(0xC000u16 as i16);
    let all_ones = _mm256_set1_epi32(-1);
    let mask_127 = _mm256_set1_epi16(127);
    let mask_7f00 = _mm256_set1_epi16(0x7F00u16 as i16);
    let mask_007f = _mm256_set1_epi16(0x007F);
    let mask_0080 = _mm256_set1_epi16(0x0080u16 as i16);

    let src_len = source.len();

    while source_offset + 32 <= src_len {
        while let Some(in_64) = source
            .get(source_offset..source_offset + 64)
            .and_then(|s| s.first_chunk::<64>())
        {
            let (s0, r1) = in_64.split_first_chunk::<16>().unwrap();
            let (s1, r2) = r1.split_first_chunk::<16>().unwrap();
            let (s2, r3) = r2.split_first_chunk::<16>().unwrap();
            let s3: &[u16; 16] = r3.try_into().unwrap();

            let u0 = load_u16x16(s0);
            let u1 = load_u16x16(s1);
            let u2 = load_u16x16(s2);
            let u3 = load_u16x16(s3);

            let or_all = _mm256_or_si256(_mm256_or_si256(u0, u1), _mm256_or_si256(u2, u3));
            if _mm256_testz_si256(or_all, mask_ge_128) == 0 {
                break;
            }

            let packed01 = _mm256_permute4x64_epi64(_mm256_packus_epi16(u0, u1), 0xD8);
            let packed23 = _mm256_permute4x64_epi64(_mm256_packus_epi16(u2, u3), 0xD8);

            let out_64 = destination[destination_offset..destination_offset + 64]
                .first_chunk_mut::<64>()
                .unwrap();
            let (d0, d1) = out_64.split_first_chunk_mut::<32>().unwrap();
            store_u8x32(d0, packed01);
            store_u8x32(d1.try_into().unwrap(), packed23);

            source_offset += 64;
            destination_offset += 64;
        }

        let Some(in_32) = source
            .get(source_offset..source_offset + 32)
            .and_then(|s| s.first_chunk::<32>())
        else {
            break;
        };

        let (s0, s1_slice) = in_32.split_first_chunk::<16>().unwrap();
        let s1: &[u16; 16] = s1_slice.try_into().unwrap();
        let v0 = load_u16x16(s0);
        let v1 = load_u16x16(s1);
        let or_combined = _mm256_or_si256(v0, v1);

        if _mm256_testz_si256(or_combined, mask_ge_128) != 0 {
            let packed = _mm256_packus_epi16(v0, v1);
            let in_order = _mm256_permute4x64_epi64(packed, 0xD8);
            let out_32 = destination[destination_offset..destination_offset + 32]
                .first_chunk_mut::<32>()
                .unwrap();
            store_u8x32(out_32, in_order);
            source_offset += 32;
            destination_offset += 32;
            continue;
        }

        if _mm256_testz_si256(v0, mask_ge_128) != 0 {
            let bytes_0_15 =
                _mm_packus_epi16(_mm256_castsi256_si128(v0), _mm256_extracti128_si256(v0, 1));
            let out_16 = destination[destination_offset..destination_offset + 16]
                .first_chunk_mut::<16>()
                .unwrap();
            store_u8x16(out_16, bytes_0_15);
            source_offset += 16;
            destination_offset += 16;
            continue;
        }

        if _mm256_testz_si256(or_combined, mask_ge_16384) != 0 {
            let cmp0 = _mm256_cmpgt_epi16(v0, mask_127);
            let cmp1 = _mm256_cmpgt_epi16(v1, mask_127);
            let cmp_both = _mm256_and_si256(cmp0, cmp1);

            if _mm256_testc_si256(cmp_both, all_ones) != 0 {
                let encoded_0 = _mm256_or_si256(
                    _mm256_and_si256(_mm256_slli_epi16(v0, 1), mask_7f00),
                    _mm256_or_si256(_mm256_and_si256(v0, mask_007f), mask_0080),
                );
                let encoded_1 = _mm256_or_si256(
                    _mm256_and_si256(_mm256_slli_epi16(v1, 1), mask_7f00),
                    _mm256_or_si256(_mm256_and_si256(v1, mask_007f), mask_0080),
                );
                let out_64 = destination[destination_offset..destination_offset + 64]
                    .first_chunk_mut::<64>()
                    .unwrap();
                let (d0, d1) = out_64.split_first_chunk_mut::<32>().unwrap();
                store_u8x32(d0, encoded_0);
                store_u8x32(d1.try_into().unwrap(), encoded_1);
                source_offset += 32;
                destination_offset += 64;
                continue;
            }

            if _mm256_testc_si256(cmp0, all_ones) != 0 {
                let encoded_0 = _mm256_or_si256(
                    _mm256_and_si256(_mm256_slli_epi16(v0, 1), mask_7f00),
                    _mm256_or_si256(_mm256_and_si256(v0, mask_007f), mask_0080),
                );
                let out_32 = destination[destination_offset..destination_offset + 32]
                    .first_chunk_mut::<32>()
                    .unwrap();
                store_u8x32(out_32, encoded_0);
                source_offset += 16;
                destination_offset += 32;
                continue;
            }

            let out_32 = destination[destination_offset..destination_offset + 32]
                .first_chunk_mut::<32>()
                .unwrap();
            let mut local_off = 0usize;
            for &value in s0 {
                let is_two = (value >= 128) as usize;
                let b0 = (value & 0x7F) | ((is_two as u16) << 7);
                let out = b0 | ((value >> 7) << 8);
                let idx = local_off.min(30);
                out_32[idx..idx + 2].copy_from_slice(&out.to_le_bytes());
                local_off += 1 + is_two;
            }
            source_offset += 16;
            destination_offset += local_off;
            continue;
        }

        let value = source[source_offset];
        source_offset += 1;
        destination_offset = encode_single_branchless(value, destination, destination_offset);
    }

    (source_offset, destination_offset)
}

/// Encodes shorts from `src_ptr` into VarInt bytes at `dest_ptr` (up to `dest_len` bytes).
/// Returns the number of bytes written to `dest_ptr`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn encode_shorts_varint(
    src_ptr: *const u16,
    src_len: u64,
    dest_ptr: *mut u8,
    dest_len: u64,
) -> u64 {
    if src_ptr.is_null() || dest_ptr.is_null() || src_len == 0 || dest_len == 0 {
        return 0;
    }

    let source = unsafe { core::slice::from_raw_parts(src_ptr, src_len as usize) };
    let destination = unsafe { core::slice::from_raw_parts_mut(dest_ptr, dest_len as usize) };

    encode_shorts_varint_slice(source, destination)
}

fn encode_shorts_varint_slice(source: &[u16], destination: &mut [u8]) -> u64 {
    #[cfg(target_arch = "x86_64")]
    let (mut source_offset, mut destination_offset) = if is_x86_feature_detected!("avx2") {
        unsafe { encode_shorts_varint_avx2(source, destination, 0, 0) }
    } else {
        (0, 0)
    };

    #[cfg(not(target_arch = "x86_64"))]
    let (mut source_offset, mut destination_offset) = (0usize, 0usize);

    let safe_end = source.len().saturating_sub(4);
    while source_offset < safe_end {
        if source_offset + 4 <= safe_end {
            if let Some((new_src, new_dest)) =
                encode_parallel_4(source, source_offset, destination, destination_offset)
            {
                source_offset = new_src;
                destination_offset = new_dest;
                continue;
            }
            if let Some((new_src, new_dest)) =
                encode_parallel_4_two_bytes(source, source_offset, destination, destination_offset)
            {
                source_offset = new_src;
                destination_offset = new_dest;
                continue;
            }
        }
        let value = source[source_offset];
        source_offset += 1;
        destination_offset = encode_single_branchless(value, destination, destination_offset);
    }

    for &value in &source[source_offset..] {
        encode_single(value, destination, &mut destination_offset);
    }

    destination_offset as u64
}

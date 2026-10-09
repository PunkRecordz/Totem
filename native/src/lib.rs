#![allow(unsafe_op_in_unsafe_fn)]

/// Helper to decode a single VarInt element from the source byte slice (slow path fallback near buffer end).
/// Returns Some(value) on success, or None if the buffer is truncated mid-VarInt.
#[inline(always)]
unsafe fn decode_single(source: &[u8], offset: &mut usize) -> Option<u16> {
    let mut value = 0u32;
    let mut shift = 0;

    while *offset < source.len() {
        let byte = unsafe { *source.get_unchecked(*offset) };
        *offset += 1;
        value |= ((byte & 0x7F) as u32) << shift;
        if (byte & 0x80) == 0 {
            return Some(value as u16);
        }
        shift += 7;
        if shift >= 21 {
            break;
        }
    }

    None
}

/// Helper to decode a single VarInt element branchlessly from an already loaded 32-bit register.
/// Returns the decoded short and the new source offset.
#[inline(always)]
fn decode_single_from_packed(packed: u32, offset: usize) -> (u16, usize) {
    let b0 = (packed & 0xFF) as u8;
    if (b0 & 0x80) == 0 {
        return (b0 as u16, offset + 1);
    }

    let b1 = ((packed >> 8) & 0xFF) as u8;
    if (b1 & 0x80) == 0 {
        let val = ((b0 & 0x7F) as u16) | (((b1 & 0x7F) as u16) << 7);
        return (val, offset + 2);
    }

    let b2 = ((packed >> 16) & 0xFF) as u8;
    let val = ((b0 & 0x7F) as u16) | (((b1 & 0x7F) as u16) << 7) | (((b2 & 0x7F) as u16) << 14);
    (val, offset + 3)
}

/// Decodes 32 VarInts at a time using AVX2 when all 32 bytes are 1-byte VarInts (MSB = 0).
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn decode_varint_shorts_avx2(
    source: &[u8],
    destination: &mut [u16],
    mut source_offset: usize,
    mut destination_offset: usize,
) -> (usize, usize) {
    use core::arch::x86_64::*;

    while source_offset + 32 <= source.len() && destination_offset + 32 <= destination.len() {
        let chunk = _mm256_loadu_si256(source.as_ptr().add(source_offset) as *const __m256i);
        let mask = _mm256_movemask_epi8(chunk);

        if mask == 0 {
            // fast path: all 32 bytes have bit 7 == 0 (1-byte VarInts)
            let low_128 = _mm256_castsi256_si128(chunk);
            let high_128 = _mm256_extracti128_si256(chunk, 1);

            let shorts_low = _mm256_cvtepu8_epi16(low_128);
            let shorts_high = _mm256_cvtepu8_epi16(high_128);

            let dest_ptr = destination.as_mut_ptr().add(destination_offset) as *mut __m256i;
            _mm256_storeu_si256(dest_ptr, shorts_low);
            _mm256_storeu_si256(dest_ptr.add(1), shorts_high);

            source_offset += 32;
            destination_offset += 32;
        } else {
            let prefix_ones = mask.trailing_zeros() as usize;
            if prefix_ones > 0 {
                // fast path: all bytes before the first MSB=1 are 1-byte VarInts
                for i in 0..prefix_ones {
                    *destination.get_unchecked_mut(destination_offset + i) = *source.get_unchecked(source_offset + i) as u16;
                }
                source_offset += prefix_ones;
                destination_offset += prefix_ones;
            }

            // decode the multi-byte VarInt at source_offset
            if source_offset + 4 <= source.len() && destination_offset < destination.len() {
                let pointer = source.as_ptr().add(source_offset) as *const u32;
                let packed = u32::from_le(core::ptr::read_unaligned(pointer));
                let (value, new_src) = decode_single_from_packed(packed, source_offset);
                *destination.get_unchecked_mut(destination_offset) = value;
                source_offset = new_src;
                destination_offset += 1;
            } else {
                break;
            }
        }
    }

    (source_offset, destination_offset)
}

/// Fallback 64-bit SWAR decoder processing 8 or 4 elements per iteration.
#[inline(always)]
unsafe fn decode_varint_shorts_swar(
    source: &[u8],
    destination: &mut [u16],
    mut source_offset: usize,
    mut destination_offset: usize,
) -> (usize, usize) {
    // 8-byte SWAR loop
    while source_offset + 8 <= source.len() && destination_offset + 8 <= destination.len() {
        let pointer = source.as_ptr().add(source_offset) as *const u64;
        let packed_64 = u64::from_le(core::ptr::read_unaligned(pointer));

        if (packed_64 & 0x8080808080808080) == 0 {
            let low_32 = packed_64 as u32 as u64;
            let expanded_low = (low_32 & 0xFF) |
                ((low_32 & 0xFF00) << 8) |
                ((low_32 & 0xFF0000) << 16) |
                ((low_32 & 0xFF000000) << 24);

            let high_32 = (packed_64 >> 32) as u32 as u64;
            let expanded_high = (high_32 & 0xFF) |
                ((high_32 & 0xFF00) << 8) |
                ((high_32 & 0xFF0000) << 16) |
                ((high_32 & 0xFF000000) << 24);

            let dest_ptr = destination.as_mut_ptr().add(destination_offset) as *mut u64;
            core::ptr::write_unaligned(dest_ptr, expanded_low.to_le());
            core::ptr::write_unaligned(dest_ptr.add(1), expanded_high.to_le());

            source_offset += 8;
            destination_offset += 8;
        } else {
            let packed = packed_64 as u32;
            let (value, new_src) = decode_single_from_packed(packed, source_offset);
            *destination.get_unchecked_mut(destination_offset) = value;
            source_offset = new_src;
            destination_offset += 1;
        }
    }

    // 4-byte fallback loop
    while source_offset + 4 <= source.len() && destination_offset + 4 <= destination.len() {
        let pointer = source.as_ptr().add(source_offset) as *const u32;
        let packed = u32::from_le(core::ptr::read_unaligned(pointer));

        if (packed & 0x80808080) == 0 {
            let packed_64 = packed as u64;
            let expanded = (packed_64 & 0xFF) |
                ((packed_64 & 0xFF00) << 8) |
                ((packed_64 & 0xFF0000) << 16) |
                ((packed_64 & 0xFF000000) << 24);

            let destination_pointer = destination.as_mut_ptr().add(destination_offset) as *mut u64;
            core::ptr::write_unaligned(destination_pointer, expanded.to_le());

            source_offset += 4;
            destination_offset += 4;
        } else {
            let (value, new_src) = decode_single_from_packed(packed, source_offset);
            *destination.get_unchecked_mut(destination_offset) = value;
            source_offset = new_src;
            destination_offset += 1;
        }
    }

    (source_offset, destination_offset)
}

/// Decodes VarInt bytes from `src_ptr` into shorts at `dest_ptr`.
/// Returns the number of bytes read from `src_ptr` on success (>= 0),
/// or a negative error code:
/// -1: Truncated input (a VarInt was cut off before completing).
/// -2: Incomplete output (buffer contained fewer VarInts than expected_size).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn decode_varint_shorts(
    src_ptr: *const u8,
    src_len: u64,
    dest_ptr: *mut u16,
    expected_size: u32,
) -> i64 {
    let source = unsafe { core::slice::from_raw_parts(src_ptr, src_len as usize) };
    let destination = unsafe { core::slice::from_raw_parts_mut(dest_ptr, expected_size as usize) };

    #[cfg(target_arch = "x86_64")]
    let (mut source_offset, mut destination_offset) = if is_x86_feature_detected!("avx2") {
        unsafe { decode_varint_shorts_avx2(source, destination, 0, 0) }
    } else {
        unsafe { decode_varint_shorts_swar(source, destination, 0, 0) }
    };

    #[cfg(not(target_arch = "x86_64"))]
    let (mut source_offset, mut destination_offset) = unsafe { decode_varint_shorts_swar(source, destination, 0, 0) };

    // finish any remaining items with 4-byte loop if AVX2 stopped near the end
    while source_offset + 4 <= source.len() && destination_offset + 4 <= destination.len() {
        let pointer = unsafe { source.as_ptr().add(source_offset) } as *const u32;
        let packed = u32::from_le(unsafe { core::ptr::read_unaligned(pointer) });

        if (packed & 0x80808080) == 0 {
            let packed_64 = packed as u64;
            let expanded = (packed_64 & 0xFF) |
                ((packed_64 & 0xFF00) << 8) |
                ((packed_64 & 0xFF0000) << 16) |
                ((packed_64 & 0xFF000000) << 24);

            let destination_pointer = unsafe { destination.as_mut_ptr().add(destination_offset) } as *mut u64;
            unsafe { core::ptr::write_unaligned(destination_pointer, expanded.to_le()) };

            source_offset += 4;
            destination_offset += 4;
        } else {
            let (value, new_src) = decode_single_from_packed(packed, source_offset);
            unsafe { *destination.get_unchecked_mut(destination_offset) = value };
            source_offset = new_src;
            destination_offset += 1;
        }
    }

    // clean up remaining elements near buffer end
    while source_offset < source.len() && destination_offset < destination.len() {
        match unsafe { decode_single(source, &mut source_offset) } {
            Some(value) => {
                unsafe { *destination.get_unchecked_mut(destination_offset) = value };
                destination_offset += 1;
            }
            None => return -1,
        }
    }

    if destination_offset < destination.len() {
        return -2;
    }

    source_offset as i64
}

/// Helper to encode a single short element into VarInt bytes using a simple loop.
#[inline(always)]
unsafe fn encode_single(value: u16, destination: &mut [u8], offset: &mut usize) {
    let mut temp_value = value as u32;

    loop {
        let mut byte = (temp_value & 0x7F) as u8;
        temp_value >>= 7;

        if temp_value != 0 {
            byte |= 0x80;
            unsafe { *destination.get_unchecked_mut(*offset) = byte };
            *offset += 1;
        } else {
            unsafe { *destination.get_unchecked_mut(*offset) = byte };
            *offset += 1;
            break;
        }
    }
}

/// Helper to encode a single short element branchlessly into a 32-bit register write.
/// Returns the new destination offset.
#[inline(always)]
unsafe fn encode_single_branchless(value: u16, destination: &mut [u8], offset: usize) -> usize {
    let val = value as i32;
    let f1 = ((127 - val) >> 31) as u32;
    let f2 = ((16383 - val) >> 31) as u32;

    let v0 = (value & 0x7F) as u32;
    let v1 = ((value >> 7) & 0x7F) as u32;
    let v2 = ((value >> 14) & 0x7F) as u32;

    let out = (v0 | (f1 & 0x80)) |
        ((v1 | (f2 & 0x80)) << 8) |
        (v2 << 16);

    let pointer = unsafe { destination.as_mut_ptr().add(offset) } as *mut u32;
    unsafe { core::ptr::write_unaligned(pointer, out.to_le()) };

    let length = 1 + (f1 & 1) + (f2 & 1);
    offset + length as usize
}

/// Helper to attempt encoding 4 elements at once using SIMD-in-a-register.
/// Returns the new offsets if successful (all 4 are 1-byte VarInts), None otherwise.
#[inline(always)]
unsafe fn encode_parallel_4(
    source: &[u16],
    source_offset: usize,
    destination: &mut [u8],
    destination_offset: usize,
) -> Option<(usize, usize)> {
    let pointer = unsafe { source.as_ptr().add(source_offset) } as *const u64;
    let packed_shorts = u64::from_le(unsafe { core::ptr::read_unaligned(pointer) });

    if (packed_shorts & 0xFF80FF80FF80FF80) == 0 {
        let compressed = ((packed_shorts & 0xFF) |
            ((packed_shorts & 0xFF0000) >> 8) |
            ((packed_shorts & 0xFF00000000) >> 16) |
            ((packed_shorts & 0xFF000000000000) >> 24)) as u32;

        let destination_pointer = unsafe { destination.as_mut_ptr().add(destination_offset) } as *mut u32;
        unsafe { core::ptr::write_unaligned(destination_pointer, compressed.to_le()); }

        Some((source_offset + 4, destination_offset + 4))
    } else {
        None
    }
}

/// AVX2 encoder processing up to 32 elements at once when elements are < 128.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn encode_shorts_varint_avx2(
    source: &[u16],
    destination: &mut [u8],
    mut source_offset: usize,
    mut destination_offset: usize,
) -> (usize, usize) {
    use core::arch::x86_64::*;

    let mask_ge_128 = _mm256_set1_epi16(0xFF80u16 as i16);

    while source_offset + 32 <= source.len() {
        let v0 = _mm256_loadu_si256(source.as_ptr().add(source_offset) as *const __m256i);
        let v1 = _mm256_loadu_si256(source.as_ptr().add(source_offset + 16) as *const __m256i);

        let or_combined = _mm256_or_si256(v0, v1);
        let test = _mm256_testz_si256(or_combined, mask_ge_128);

        if test != 0 {
            // all 32 shorts are < 128 (1-byte VarInts)
            let low_v0 = _mm256_castsi256_si128(v0);
            let high_v0 = _mm256_extracti128_si256(v0, 1);
            let low_v1 = _mm256_castsi256_si128(v1);
            let high_v1 = _mm256_extracti128_si256(v1, 1);

            let bytes_0_15 = _mm_packus_epi16(low_v0, high_v0);
            let bytes_16_31 = _mm_packus_epi16(low_v1, high_v1);

            let dest_ptr = destination.as_mut_ptr().add(destination_offset);
            _mm_storeu_si128(dest_ptr as *mut __m128i, bytes_0_15);
            _mm_storeu_si128(dest_ptr.add(16) as *mut __m128i, bytes_16_31);

            source_offset += 32;
            destination_offset += 32;
        } else if _mm256_testz_si256(v0, mask_ge_128) != 0 {
            let low_v0 = _mm256_castsi256_si128(v0);
            let high_v0 = _mm256_extracti128_si256(v0, 1);
            let bytes_0_15 = _mm_packus_epi16(low_v0, high_v0);
            let dest_ptr = destination.as_mut_ptr().add(destination_offset);
            _mm_storeu_si128(dest_ptr as *mut __m128i, bytes_0_15);

            source_offset += 16;
            destination_offset += 16;
        } else {
            let value = *source.get_unchecked(source_offset);
            source_offset += 1;
            destination_offset = encode_single_branchless(value, destination, destination_offset);
        }
    }

    (source_offset, destination_offset)
}

/// Encodes shorts from `src_ptr` into VarInt bytes at `dest_ptr`.
/// Returns the number of bytes written to `dest_ptr`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn encode_shorts_varint(
    src_ptr: *const u16,
    src_len: u64,
    dest_ptr: *mut u8,
) -> u64 {
    let source = unsafe { core::slice::from_raw_parts(src_ptr, src_len as usize) };
    let destination = unsafe { core::slice::from_raw_parts_mut(dest_ptr, (src_len * 4) as usize) };

    #[cfg(target_arch = "x86_64")]
    let (mut source_offset, mut destination_offset) = if is_x86_feature_detected!("avx2") {
        unsafe { encode_shorts_varint_avx2(source, destination, 0, 0) }
    } else {
        (0, 0)
    };

    #[cfg(not(target_arch = "x86_64"))]
    let (mut source_offset, mut destination_offset) = (0usize, 0usize);

    // parallel fast path: encode 4 elements at once, but stop at least 4 elements before the end
    // to guarantee that encode_single_branchless (which writes a u32) never overshoots the buffer boundary
    let safe_end = source.len().saturating_sub(4);
    while source_offset < safe_end {
        if source_offset + 4 <= safe_end {
            if let Some((new_src, new_dest)) = unsafe { encode_parallel_4(source, source_offset, destination, destination_offset) } {
                source_offset = new_src;
                destination_offset = new_dest;
                continue;
            }
        }
        let value = unsafe { *source.get_unchecked(source_offset) };
        source_offset += 1;
        destination_offset = unsafe { encode_single_branchless(value, destination, destination_offset) };
    }

    // clean up remaining elements near buffer end strictly byte-by-byte (zero overshoot)
    while source_offset < source.len() {
        let value = unsafe { *source.get_unchecked(source_offset) };
        source_offset += 1;
        unsafe { encode_single(value, destination, &mut destination_offset); }
    }

    destination_offset as u64
}

/// Calculates the exact number of bytes required to encode `src_len` shorts as VarInt.
/// Highly optimized with AVX2 SIMD on x86_64.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn varint_encoded_size_avx2(src: *const u16, len: usize) -> usize {
    use core::arch::x86_64::*;

    let mut offset = 0;
    let mut extra_bytes = 0usize;
    let mask_ge_128 = _mm256_set1_epi16(0xFF80u16 as i16);
    let mask_ge_16384 = _mm256_set1_epi16(0xC000u16 as i16);
    let zero = _mm256_setzero_si256();

    while offset + 16 <= len {
        let v = _mm256_loadu_si256(src.add(offset) as *const __m256i);

        // Fast check: if all 16 shorts are < 128, 0 extra bytes needed
        if _mm256_testz_si256(v, mask_ge_128) != 0 {
            offset += 16;
            continue;
        }

        // Count how many shorts are >= 128
        let t128 = _mm256_and_si256(v, mask_ge_128);
        let eq_zero_128 = _mm256_cmpeq_epi16(t128, zero);
        let mask_128 = _mm256_movemask_epi8(eq_zero_128) as u32;
        let count_ge_128 = 16 - (mask_128.count_ones() / 2) as usize;
        extra_bytes += count_ge_128;

        // Count how many shorts are >= 16384
        let t16384 = _mm256_and_si256(v, mask_ge_16384);
        let eq_zero_16384 = _mm256_cmpeq_epi16(t16384, zero);
        let mask_16384 = _mm256_movemask_epi8(eq_zero_16384) as u32;
        let count_ge_16384 = 16 - (mask_16384.count_ones() / 2) as usize;
        extra_bytes += count_ge_16384;

        offset += 16;
    }

    while offset < len {
        let val = *src.add(offset);
        if val >= 128 {
            extra_bytes += 1;
            if val >= 16384 {
                extra_bytes += 1;
            }
        }
        offset += 1;
    }

    len + extra_bytes
}

/// Fallback calculation for encoded VarInt size using 64-bit SWAR.
#[inline(always)]
unsafe fn varint_encoded_size_swar(src: *const u16, len: usize) -> usize {
    let mut offset = 0;
    let mut extra_bytes = 0usize;

    while offset + 4 <= len {
        let pointer = src.add(offset) as *const u64;
        let packed = u64::from_le(core::ptr::read_unaligned(pointer));

        if (packed & 0xFF80FF80FF80FF80) == 0 {
            offset += 4;
            continue;
        }

        for i in 0..4 {
            let val = (packed >> (i * 16)) as u16;
            if val >= 128 {
                extra_bytes += 1;
                if val >= 16384 {
                    extra_bytes += 1;
                }
            }
        }
        offset += 4;
    }

    while offset < len {
        let val = *src.add(offset);
        if val >= 128 {
            extra_bytes += 1;
            if val >= 16384 {
                extra_bytes += 1;
            }
        }
        offset += 1;
    }

    len + extra_bytes
}

/// Calculates the exact number of bytes required to encode `src_len` shorts as VarInt.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn varint_encoded_size(
    src_ptr: *const u16,
    src_len: u64,
) -> u64 {
    let len = src_len as usize;

    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") {
        return unsafe { varint_encoded_size_avx2(src_ptr, len) } as u64;
    }

    unsafe { varint_encoded_size_swar(src_ptr, len) as u64 }
}

/// Counts the number of terminal VarInt bytes (MSB == 0) in the byte slice using AVX2.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn count_varint_terminals_avx2(src: *const u8, len: usize) -> usize {
    use core::arch::x86_64::*;

    let mut offset = 0;
    let mut count = 0usize;

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
/// In standard Minecraft VarInt encoding, each terminal byte corresponds to exactly one decoded integer.
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

/// Identifies VarInt terminal boundaries and computes partition short offsets in a single AVX2 pass.
/// Writes `p` elements into `out_byte_starts`, `out_short_offsets`, and `out_short_counts`.
/// Writes `byte_starts[p] = src_len` at `out_byte_starts + p`.
/// Returns the total number of decoded shorts across the entire stream.
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
        // fallback for unusually high core counts
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

    while i + 16 <= len {
        let ptr = slice.as_mut_ptr().add(i) as *mut __m256i;
        let data = _mm256_loadu_si256(ptr);
        let mask = _mm256_cmpeq_epi16(data, target_vec);
        let result = _mm256_blendv_epi8(data, repl_vec, mask);
        _mm256_storeu_si256(ptr, result);
        i += 16;
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_count_varint_terminals() {
        let sample = vec![
            0x01,                   // 1 terminal (1 byte)
            0x80, 0x01,             // 1 terminal (2 bytes)
            0x80, 0x80, 0x01,       // 1 terminal (3 bytes)
            0x05,                   // 1 terminal (1 byte)
            0x00,                   // 1 terminal (1 byte)
        ];
        let count = unsafe { count_varint_terminals(sample.as_ptr(), sample.len() as u64) };
        assert_eq!(count, 5);
    }

    #[test]
    fn test_partition_varint_stream() {
        let sample = vec![
            0x01,                   // short 0
            0x80, 0x01,             // short 1
            0x80, 0x80, 0x01,       // short 2
            0x05,                   // short 3
            0x00,                   // short 4
            0x10,                   // short 5
        ];
        let mut byte_starts = [0u64; 3];
        let mut short_offsets = [0u32; 2];
        let mut short_counts = [0u32; 2];

        let total = unsafe {
            partition_varint_stream(
                sample.as_ptr(),
                sample.len() as u64,
                2,
                byte_starts.as_mut_ptr(),
                short_offsets.as_mut_ptr(),
                short_counts.as_mut_ptr(),
            )
        };

        assert_eq!(total, 6);
        assert_eq!(short_offsets[0], 0);
        assert_eq!(short_offsets[1] + short_counts[1], 6);
        assert_eq!(byte_starts[0], 0);
        assert_eq!(byte_starts[2], sample.len() as u64);
    }

    #[test]
    fn test_varint_encoded_size_correctness() {
        let mut sample = Vec::new();
        // 1-byte VarInts (< 128)
        sample.extend(vec![0u16, 1, 50, 127]);
        // 2-byte VarInts (128..16383)
        sample.extend(vec![128u16, 255, 1000, 16383]);
        // 3-byte VarInts (>= 16384)
        sample.extend(vec![16384u16, 32767, 50000, 65535]);

        let calculated_size = unsafe { varint_encoded_size(sample.as_ptr(), sample.len() as u64) };
        let mut encoded = vec![0u8; sample.len() * 4];
        let actual_size = unsafe { encode_shorts_varint(sample.as_ptr(), sample.len() as u64, encoded.as_mut_ptr()) };

        assert_eq!(calculated_size, actual_size);
        assert_eq!(actual_size, 4 * 1 + 4 * 2 + 4 * 3); // 24 bytes
    }

    #[test]
    fn test_roundtrip_all_sizes() {
        let mut sample = Vec::new();
        for i in 0..1000u16 {
            sample.push(i % 128); // 1-byte
            sample.push(128 + (i % 5000)); // 2-byte
            sample.push(20000 + (i % 40000)); // 3-byte
        }

        let size = unsafe { varint_encoded_size(sample.as_ptr(), sample.len() as u64) };
        let mut encoded = vec![0u8; size as usize];
        let written = unsafe { encode_shorts_varint(sample.as_ptr(), sample.len() as u64, encoded.as_mut_ptr()) };
        assert_eq!(size, written);

        let mut decoded = vec![0u16; sample.len()];
        let read = unsafe { decode_varint_shorts(encoded.as_ptr(), written, decoded.as_mut_ptr(), sample.len() as u32) };
        assert_eq!(read, written as i64);
        assert_eq!(sample, decoded);
    }

    #[test]
    fn test_replace_shorts() {
        let mut sample = vec![0i16, 1, 0, 2, 3, 0, 4, 5, 0, 0, 6, 7, 8, 9, 10, 0, 11, 0];
        unsafe {
            replace_shorts(sample.as_mut_ptr(), sample.len() as u64, 0, 42);
        }
        assert_eq!(sample, vec![42i16, 1, 42, 2, 3, 42, 4, 5, 42, 42, 6, 7, 8, 9, 10, 42, 11, 42]);
    }

    #[test]
    fn test_fill_shorts() {
        let mut sample = vec![0i16; 35];
        unsafe {
            fill_shorts(sample.as_mut_ptr(), sample.len() as u64, 99);
        }
        assert_eq!(sample, vec![99i16; 35]);
    }
}

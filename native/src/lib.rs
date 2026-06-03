/// Helper to decode a single VarInt element from the source byte slice (slow path fallback near buffer end).
unsafe fn decode_single(source: &[u8], offset: &mut usize) -> u16 {
    let mut value = 0u32;
    let mut shift = 0;

    loop {
        let byte = unsafe { *source.get_unchecked(*offset) };
        *offset += 1;
        value |= ((byte & 0x7F) as u32) << shift;
        if (byte & 0x80) == 0 {
            break;
        }
        shift += 7;
    }

    value as u16
}

/// Helper to decode a single VarInt element branchlessly from an already loaded 32-bit register.
/// Returns the decoded short and the new source offset.
#[inline(always)]
fn decode_single_from_packed(packed: u32, offset: usize) -> (u16, usize) {
    let v0 = packed & 0x7F;
    let b0_msb = (packed & 0x80) >> 7;
    let b1_msb = (packed & 0x8000) >> 15;

    let m1 = 0u32.wrapping_sub(b0_msb);
    let b0_and_b1 = b0_msb & b1_msb;
    let m2 = 0u32.wrapping_sub(b0_and_b1);

    let v1_contrib = ((packed >> 8) & 0x7F) & m1;
    let v2_contrib = ((packed >> 16) & 0x7F) & m2;

    let value = v0 | (v1_contrib << 7) | (v2_contrib << 14);
    let length = 1 + b0_msb + b0_and_b1;

    (value as u16, offset + length as usize)
}

/// Decodes VarInt bytes from `src_ptr` into shorts at `dest_ptr`.
/// Returns the number of bytes read from `src_ptr`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn decode_varint_shorts(
    src_ptr: *const u8,
    src_len: u64,
    dest_ptr: *mut u16,
    expected_size: u32,
) -> u64 {
    let source = unsafe { core::slice::from_raw_parts(src_ptr, src_len as usize) };
    let destination = unsafe { core::slice::from_raw_parts_mut(dest_ptr, expected_size as usize) };

    let mut source_offset = 0;
    let mut destination_offset = 0;

    // main loop: process elements using a single 32-bit read per iteration
    while source_offset + 4 <= source.len() && destination_offset + 4 <= destination.len() {
        let pointer = unsafe { source.as_ptr().add(source_offset) } as *const u32;
        let packed = u32::from_le(unsafe { core::ptr::read_unaligned(pointer) });

        if (packed & 0x80808080) == 0 {
            // fast path: decode 4 1-byte VarInts in parallel
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
            // fallback: decode a single element from the already loaded 'packed' register
            let (value, new_src) = decode_single_from_packed(packed, source_offset);
            unsafe { *destination.get_unchecked_mut(destination_offset) = value };
            source_offset = new_src;
            destination_offset += 1;
        }
    }

    // clean up remaining elements near buffer end
    while source_offset < source.len() && destination_offset < destination.len() {
        let value = unsafe { decode_single(source, &mut source_offset) };
        unsafe { *destination.get_unchecked_mut(destination_offset) = value };
        destination_offset += 1;
    }

    source_offset as u64
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

    let mut source_offset = 0;
    let mut destination_offset = 0;

    // parallel fast path: encode 4 elements at once
    while source_offset + 4 <= source.len() {
        if let Some((new_src, new_dest)) = unsafe { encode_parallel_4(source, source_offset, destination, destination_offset) } {
            source_offset = new_src;
            destination_offset = new_dest;
        } else {
            let value = unsafe { *source.get_unchecked(source_offset) };
            source_offset += 1;
            destination_offset = unsafe { encode_single_branchless(value, destination, destination_offset) };
        }
    }

    // clean up remaining elements near buffer end
    while source_offset < source.len() {
        let value = unsafe { *source.get_unchecked(source_offset) };
        source_offset += 1;
        unsafe { encode_single(value, destination, &mut destination_offset); }
    }

    destination_offset as u64
}

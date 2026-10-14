/// Helper to encode a single short element into VarInt bytes using a simple loop.
#[inline(always)]
pub(super) unsafe fn encode_single(value: u16, destination: &mut [u8], offset: &mut usize) {
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
pub(super) unsafe fn encode_single_branchless(
    value: u16,
    destination: &mut [u8],
    offset: usize,
) -> usize {
    let val = value as i32;
    let f1 = ((127 - val) >> 31) as u32;
    let f2 = ((16383 - val) >> 31) as u32;

    let v0 = (value & 0x7F) as u32;
    let v1 = ((value >> 7) & 0x7F) as u32;
    let v2 = ((value >> 14) & 0x7F) as u32;

    let out = (v0 | (f1 & 0x80)) | ((v1 | (f2 & 0x80)) << 8) | (v2 << 16);

    let pointer = unsafe { destination.as_mut_ptr().add(offset) } as *mut u32;
    unsafe { core::ptr::write_unaligned(pointer, out.to_le()) };

    let length = 1 + (f1 & 1) + (f2 & 1);
    offset + length as usize
}

/// Helper to attempt encoding 4 elements at once using SIMD-in-a-register.
#[inline(always)]
pub(super) unsafe fn encode_parallel_4(
    source: &[u16],
    source_offset: usize,
    destination: &mut [u8],
    destination_offset: usize,
) -> Option<(usize, usize)> {
    let pointer = unsafe { source.as_ptr().add(source_offset) } as *const u64;
    let packed_shorts = u64::from_le(unsafe { core::ptr::read_unaligned(pointer) });

    if (packed_shorts & 0xFF80FF80FF80FF80) == 0 {
        let compressed = ((packed_shorts & 0xFF)
            | ((packed_shorts & 0xFF0000) >> 8)
            | ((packed_shorts & 0xFF00000000) >> 16)
            | ((packed_shorts & 0xFF000000000000) >> 24)) as u32;

        let destination_pointer =
            unsafe { destination.as_mut_ptr().add(destination_offset) } as *mut u32;
        unsafe { core::ptr::write_unaligned(destination_pointer, compressed.to_le()) };

        Some((source_offset + 4, destination_offset + 4))
    } else {
        None
    }
}

/// Helper to attempt encoding 4 elements at once when all 4 are 2-byte VarInts ([128, 16384)).
#[inline(always)]
pub(super) unsafe fn encode_parallel_4_two_bytes(
    source: &[u16],
    source_offset: usize,
    destination: &mut [u8],
    destination_offset: usize,
) -> Option<(usize, usize)> {
    let pointer = unsafe { source.as_ptr().add(source_offset) } as *const u64;
    let packed_shorts = u64::from_le(unsafe { core::ptr::read_unaligned(pointer) });

    if (packed_shorts & 0xC000C000C000C000) != 0 {
        return None;
    }

    let short_0 = packed_shorts as u16;
    let short_1 = (packed_shorts >> 16) as u16;
    let short_2 = (packed_shorts >> 32) as u16;
    let short_3 = (packed_shorts >> 48) as u16;

    if short_0 >= 128 && short_1 >= 128 && short_2 >= 128 && short_3 >= 128 {
        let low_seven = packed_shorts & 0x007F007F007F007F;
        let byte_zero = low_seven | 0x0080008000800080;
        let high_seven = (packed_shorts >> 7) & 0x007F007F007F007F;
        let byte_one = high_seven << 8;
        let encoded = byte_zero | byte_one;

        let destination_pointer =
            unsafe { destination.as_mut_ptr().add(destination_offset) } as *mut u64;
        unsafe { core::ptr::write_unaligned(destination_pointer, encoded.to_le()) };

        Some((source_offset + 4, destination_offset + 8))
    } else {
        None
    }
}

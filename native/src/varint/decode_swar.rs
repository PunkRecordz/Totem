/// Helper to decode a single VarInt element from the source byte slice (slow path fallback near buffer end).
/// Returns Some(value) on success, or None if the buffer is truncated mid-VarInt.
#[inline(always)]
pub unsafe fn decode_single(source: &[u8], offset: &mut usize) -> Option<u16> {
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
pub fn decode_single_from_packed(packed: u32, offset: usize) -> (u16, usize) {
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

/// Fallback 64-bit SWAR decoder processing 8 or 4 elements per iteration.
#[inline(always)]
pub unsafe fn decode_varint_shorts_swar(
    source: &[u8],
    destination: &mut [u16],
    mut source_offset: usize,
    mut destination_offset: usize,
) -> (usize, usize) {
    while source_offset + 8 <= source.len() && destination_offset + 8 <= destination.len() {
        let pointer = source.as_ptr().add(source_offset) as *const u64;
        let packed_64 = u64::from_le(core::ptr::read_unaligned(pointer));

        if (packed_64 & 0x8080808080808080) == 0 {
            let low_32 = packed_64 as u32 as u64;
            let expanded_low = (low_32 & 0xFF)
                | ((low_32 & 0xFF00) << 8)
                | ((low_32 & 0xFF0000) << 16)
                | ((low_32 & 0xFF000000) << 24);

            let high_32 = (packed_64 >> 32) as u32 as u64;
            let expanded_high = (high_32 & 0xFF)
                | ((high_32 & 0xFF00) << 8)
                | ((high_32 & 0xFF0000) << 16)
                | ((high_32 & 0xFF000000) << 24);

            let dest_ptr = destination.as_mut_ptr().add(destination_offset) as *mut u64;
            core::ptr::write_unaligned(dest_ptr, expanded_low.to_le());
            core::ptr::write_unaligned(dest_ptr.add(1), expanded_high.to_le());

            source_offset += 8;
            destination_offset += 8;
        } else if (packed_64 & 0x8080808080808080) == 0x0080008000800080
            && destination_offset + 4 <= destination.len()
        {
            let low = packed_64 & 0x007F007F007F007F;
            let high = (packed_64 >> 1) & 0x3F803F803F803F80;
            let decoded_64 = low | high;

            let dest_ptr = destination.as_mut_ptr().add(destination_offset) as *mut u64;
            core::ptr::write_unaligned(dest_ptr, decoded_64.to_le());

            source_offset += 8;
            destination_offset += 4;
        } else {
            let packed = packed_64 as u32;
            let (value, new_src) = decode_single_from_packed(packed, source_offset);
            *destination.get_unchecked_mut(destination_offset) = value;
            source_offset = new_src;
            destination_offset += 1;
        }
    }

    decode_varint_shorts_swar_4(source, destination, source_offset, destination_offset)
}

#[inline(always)]
pub unsafe fn decode_varint_shorts_swar_4(
    source: &[u8],
    destination: &mut [u16],
    mut source_offset: usize,
    mut destination_offset: usize,
) -> (usize, usize) {
    while source_offset + 4 <= source.len() && destination_offset + 4 <= destination.len() {
        let pointer = source.as_ptr().add(source_offset) as *const u32;
        let packed = u32::from_le(core::ptr::read_unaligned(pointer));

        if (packed & 0x80808080) == 0 {
            let packed_64 = packed as u64;
            let expanded = (packed_64 & 0xFF)
                | ((packed_64 & 0xFF00) << 8)
                | ((packed_64 & 0xFF0000) << 16)
                | ((packed_64 & 0xFF000000) << 24);

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

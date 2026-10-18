/// Helper to decode a single VarInt element from the source byte slice (slow path fallback near buffer end).
/// Returns Some(value) on success, or None if the buffer is truncated mid-VarInt.
#[inline(always)]
pub fn decode_single(source: &[u8], offset: &mut usize) -> Option<u16> {
    let mut value = 0u32;
    let mut shift = 0;

    for &byte in &source[*offset..] {
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
/// Returns `Some((decoded_short, new_source_offset))` on valid 1..=3 byte VarInts, or `None` if the VarInt exceeds 3 bytes.
#[inline(always)]
pub fn decode_single_from_packed(packed: u32, offset: usize) -> Option<(u16, usize)> {
    let b0 = (packed & 0xFF) as u8;
    if (b0 & 0x80) == 0 {
        return Some((b0 as u16, offset + 1));
    }

    let b1 = ((packed >> 8) & 0xFF) as u8;
    if (b1 & 0x80) == 0 {
        let val = ((b0 & 0x7F) as u16) | (((b1 & 0x7F) as u16) << 7);
        return Some((val, offset + 2));
    }

    let b2 = ((packed >> 16) & 0xFF) as u8;
    if (b2 & 0x80) == 0 {
        let val = ((b0 & 0x7F) as u16) | (((b1 & 0x7F) as u16) << 7) | ((b2 as u16) << 14);
        return Some((val, offset + 3));
    }

    None
}

/// Fallback 64-bit SWAR decoder processing 8 or 4 elements per iteration.
#[inline(always)]
pub fn decode_varint_shorts_swar(
    source: &[u8],
    destination: &mut [u16],
    mut source_offset: usize,
    mut destination_offset: usize,
) -> (usize, usize) {
    while let (Some(src_chunk), Some(dst_chunk)) = (
        source
            .get(source_offset..source_offset + 8)
            .and_then(|s| s.first_chunk::<8>()),
        destination
            .get_mut(destination_offset..destination_offset + 8)
            .and_then(|d| d.first_chunk_mut::<8>()),
    ) {
        let packed_64 = u64::from_le_bytes(*src_chunk);

        if (packed_64 & 0x8080808080808080) == 0 {
            *dst_chunk = src_chunk.map(u16::from);
            source_offset += 8;
            destination_offset += 8;
        } else if (packed_64 & 0x8080808080808080) == 0x0080008000800080 {
            let b = *src_chunk;
            dst_chunk[0] = ((b[0] & 0x7F) as u16) | (((b[1] & 0x7F) as u16) << 7);
            dst_chunk[1] = ((b[2] & 0x7F) as u16) | (((b[3] & 0x7F) as u16) << 7);
            dst_chunk[2] = ((b[4] & 0x7F) as u16) | (((b[5] & 0x7F) as u16) << 7);
            dst_chunk[3] = ((b[6] & 0x7F) as u16) | (((b[7] & 0x7F) as u16) << 7);

            source_offset += 8;
            destination_offset += 4;
        } else {
            let packed = packed_64 as u32;
            match decode_single_from_packed(packed, source_offset) {
                Some((value, new_src)) => {
                    dst_chunk[0] = value;
                    source_offset = new_src;
                    destination_offset += 1;
                }
                None => break,
            }
        }
    }

    decode_varint_shorts_swar_4(source, destination, source_offset, destination_offset)
}

#[inline(always)]
pub fn decode_varint_shorts_swar_4(
    source: &[u8],
    destination: &mut [u16],
    mut source_offset: usize,
    mut destination_offset: usize,
) -> (usize, usize) {
    while let (Some(src_chunk), Some(dst_chunk)) = (
        source
            .get(source_offset..source_offset + 4)
            .and_then(|s| s.first_chunk::<4>()),
        destination
            .get_mut(destination_offset..destination_offset + 4)
            .and_then(|d| d.first_chunk_mut::<4>()),
    ) {
        let packed = u32::from_le_bytes(*src_chunk);

        if (packed & 0x80808080) == 0 {
            *dst_chunk = src_chunk.map(u16::from);
            source_offset += 4;
            destination_offset += 4;
        } else {
            match decode_single_from_packed(packed, source_offset) {
                Some((value, new_src)) => {
                    dst_chunk[0] = value;
                    source_offset = new_src;
                    destination_offset += 1;
                }
                None => break,
            }
        }
    }

    (source_offset, destination_offset)
}

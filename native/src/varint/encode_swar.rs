/// Helper to encode a single short element into VarInt bytes using a simple loop.
#[inline(always)]
pub(super) fn encode_single(value: u16, destination: &mut [u8], offset: &mut usize) {
    let mut temp_value = value as u32;

    loop {
        let mut byte = (temp_value & 0x7F) as u8;
        temp_value >>= 7;

        if temp_value != 0 {
            byte |= 0x80;
            destination[*offset] = byte;
            *offset += 1;
        } else {
            destination[*offset] = byte;
            *offset += 1;
            break;
        }
    }
}

/// Helper to encode a single short element branchlessly into a 32-bit register write.
/// Returns the new destination offset.
#[inline(always)]
pub(super) fn encode_single_branchless(
    value: u16,
    destination: &mut [u8],
    offset: usize,
) -> usize {
    let Some(dest_chunk) = destination
        .get_mut(offset..offset + 4)
        .and_then(|s| s.first_chunk_mut::<4>())
    else {
        let mut next = offset;
        encode_single(value, destination, &mut next);
        return next;
    };

    let val = value as i32;
    let f1 = ((127 - val) >> 31) as u32;
    let f2 = ((16383 - val) >> 31) as u32;

    let v0 = (value & 0x7F) as u32;
    let v1 = ((value >> 7) & 0x7F) as u32;
    let v2 = ((value >> 14) & 0x7F) as u32;

    let out = (v0 | (f1 & 0x80)) | ((v1 | (f2 & 0x80)) << 8) | (v2 << 16);
    *dest_chunk = out.to_le_bytes();

    let length = 1 + (f1 & 1) + (f2 & 1);
    offset + length as usize
}

/// Helper to attempt encoding 4 elements at once using SIMD-in-a-register.
#[inline(always)]
pub(super) fn encode_parallel_4(
    source: &[u16],
    source_offset: usize,
    destination: &mut [u8],
    destination_offset: usize,
) -> Option<(usize, usize)> {
    let &[s0, s1, s2, s3] = source
        .get(source_offset..source_offset + 4)?
        .first_chunk::<4>()?;

    if ((s0 | s1 | s2 | s3) & 0xFF80) == 0 {
        let dest_chunk = destination
            .get_mut(destination_offset..destination_offset + 4)?
            .first_chunk_mut::<4>()?;
        *dest_chunk = [s0 as u8, s1 as u8, s2 as u8, s3 as u8];
        Some((source_offset + 4, destination_offset + 4))
    } else {
        None
    }
}

/// Helper to attempt encoding 4 elements at once when all 4 are 2-byte VarInts ([128, 16384)).
#[inline(always)]
pub(super) fn encode_parallel_4_two_bytes(
    source: &[u16],
    source_offset: usize,
    destination: &mut [u8],
    destination_offset: usize,
) -> Option<(usize, usize)> {
    let &[short_0, short_1, short_2, short_3] = source
        .get(source_offset..source_offset + 4)?
        .first_chunk::<4>()?;

    if ((short_0 | short_1 | short_2 | short_3) & 0xC000) != 0 {
        return None;
    }

    if short_0 >= 128 && short_1 >= 128 && short_2 >= 128 && short_3 >= 128 {
        let dest_chunk = destination
            .get_mut(destination_offset..destination_offset + 8)?
            .first_chunk_mut::<8>()?;
        let packed_shorts = (short_0 as u64)
            | ((short_1 as u64) << 16)
            | ((short_2 as u64) << 32)
            | ((short_3 as u64) << 48);
        let low_seven = packed_shorts & 0x007F007F007F007F;
        let byte_zero = low_seven | 0x0080008000800080;
        let high_seven = (packed_shorts >> 7) & 0x007F007F007F007F;
        let byte_one = high_seven << 8;
        let encoded = byte_zero | byte_one;

        *dest_chunk = encoded.to_le_bytes();

        Some((source_offset + 4, destination_offset + 8))
    } else {
        None
    }
}

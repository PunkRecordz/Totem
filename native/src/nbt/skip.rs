pub const MAX_NBT_DEPTH: u32 = 512;

#[derive(Copy, Clone, PartialEq, Eq)]
pub enum NbtSkipError {
    Truncated,
    Malformed,
    DepthExceeded,
}

#[derive(Copy, Clone)]
enum ContainerFrame {
    Compound,
    List { elem_type: u8, remaining: usize },
}

#[inline(always)]
pub fn advance(rem: &[u8], bytes: usize) -> Result<&[u8], NbtSkipError> {
    rem.get(bytes..).ok_or(NbtSkipError::Truncated)
}

#[inline(always)]
pub fn read_be_u16(rem: &[u8]) -> Result<(u16, &[u8]), NbtSkipError> {
    let (bytes, rest) = rem.split_first_chunk::<2>().ok_or(NbtSkipError::Truncated)?;
    Ok((u16::from_be_bytes(*bytes), rest))
}

#[inline(always)]
pub fn read_be_i32(rem: &[u8]) -> Result<(i32, &[u8]), NbtSkipError> {
    let (bytes, rest) = rem.split_first_chunk::<4>().ok_or(NbtSkipError::Truncated)?;
    Ok((i32::from_be_bytes(*bytes), rest))
}

#[inline(always)]
pub fn skip_leaf_tag(type_id: u8, rem: &[u8]) -> Result<&[u8], NbtSkipError> {
    match type_id {
        0 => Ok(rem),
        1 => advance(rem, 1),
        2 => advance(rem, 2),
        3 | 5 => advance(rem, 4),
        4 | 6 => advance(rem, 8),
        7 => {
            let (len, payload) = read_be_i32(rem)?;
            if len < 0 {
                return Err(NbtSkipError::Malformed);
            }
            advance(payload, len as usize)
        }
        8 => {
            let (len, payload) = read_be_u16(rem)?;
            advance(payload, len as usize)
        }
        11 => {
            let (len, payload) = read_be_i32(rem)?;
            if len < 0 {
                return Err(NbtSkipError::Malformed);
            }
            let byte_len = (len as usize)
                .checked_mul(4)
                .ok_or(NbtSkipError::Malformed)?;
            advance(payload, byte_len)
        }
        12 => {
            let (len, payload) = read_be_i32(rem)?;
            if len < 0 {
                return Err(NbtSkipError::Malformed);
            }
            let byte_len = (len as usize)
                .checked_mul(8)
                .ok_or(NbtSkipError::Malformed)?;
            advance(payload, byte_len)
        }
        _ => Err(NbtSkipError::Malformed),
    }
}

#[inline(always)]
pub fn validate_matched_tag(type_id: u8, rem: &[u8]) -> Result<(), NbtSkipError> {
    match type_id {
        9 => {
            let (&elem_type, after_type) = rem.split_first().ok_or(NbtSkipError::Truncated)?;
            let (count, payload) = read_be_i32(after_type)?;
            if count < 0 {
                return Err(NbtSkipError::Malformed);
            }
            if count == 0 {
                return Ok(());
            }
            if elem_type == 0 || elem_type > 12 {
                return Err(NbtSkipError::Malformed);
            }
            let ucount = count as usize;
            match elem_type {
                1 => {
                    advance(payload, ucount)?;
                }
                2 => {
                    let bytes = ucount.checked_mul(2).ok_or(NbtSkipError::Malformed)?;
                    advance(payload, bytes)?;
                }
                3 | 5 => {
                    let bytes = ucount.checked_mul(4).ok_or(NbtSkipError::Malformed)?;
                    advance(payload, bytes)?;
                }
                4 | 6 => {
                    let bytes = ucount.checked_mul(8).ok_or(NbtSkipError::Malformed)?;
                    advance(payload, bytes)?;
                }
                _ => {}
            }
            Ok(())
        }
        10 => {
            let (&first_type, _) = rem.split_first().ok_or(NbtSkipError::Truncated)?;
            if first_type > 12 {
                return Err(NbtSkipError::Malformed);
            }
            Ok(())
        }
        _ => {
            let _ = skip_leaf_tag(type_id, rem)?;
            Ok(())
        }
    }
}

#[inline(always)]
fn skip_or_push_tag<'a>(
    type_id: u8,
    rem: &'a [u8],
    depth: &mut u32,
    stack: &mut [ContainerFrame; (MAX_NBT_DEPTH as usize) + 1],
    stack_len: &mut usize,
) -> Result<&'a [u8], NbtSkipError> {
    if *depth > MAX_NBT_DEPTH {
        return Err(NbtSkipError::DepthExceeded);
    }

    match type_id {
        9 => {
            let (&elem_type, after_type) = rem.split_first().ok_or(NbtSkipError::Truncated)?;
            let (count, payload) = read_be_i32(after_type)?;
            if count < 0 {
                return Err(NbtSkipError::Malformed);
            }
            if count == 0 {
                return Ok(payload);
            }
            if elem_type == 0 || elem_type > 12 {
                return Err(NbtSkipError::Malformed);
            }
            if *depth + 1 > MAX_NBT_DEPTH {
                return Err(NbtSkipError::DepthExceeded);
            }
            let ucount = count as usize;
            match elem_type {
                1 => advance(payload, ucount),
                2 => advance(payload, ucount.checked_mul(2).ok_or(NbtSkipError::Malformed)?),
                3 | 5 => advance(payload, ucount.checked_mul(4).ok_or(NbtSkipError::Malformed)?),
                4 | 6 => advance(payload, ucount.checked_mul(8).ok_or(NbtSkipError::Malformed)?),
                _ => {
                    stack[*stack_len] = ContainerFrame::List {
                        elem_type,
                        remaining: ucount,
                    };
                    *stack_len += 1;
                    *depth += 1;
                    Ok(payload)
                }
            }
        }
        10 => {
            stack[*stack_len] = ContainerFrame::Compound;
            *stack_len += 1;
            *depth += 1;
            Ok(rem)
        }
        _ => skip_leaf_tag(type_id, rem),
    }
}

#[inline(always)]
pub fn skip_nbt_tag(
    type_id: u8,
    rem: &[u8],
    initial_depth: u32,
) -> Result<&[u8], NbtSkipError> {
    if initial_depth > MAX_NBT_DEPTH {
        return Err(NbtSkipError::DepthExceeded);
    }

    if type_id != 9 && type_id != 10 {
        return skip_leaf_tag(type_id, rem);
    }

    let mut depth = initial_depth;
    let mut stack = [ContainerFrame::Compound; (MAX_NBT_DEPTH as usize) + 1];
    let mut stack_len: usize = 0;

    let mut curr = skip_or_push_tag(type_id, rem, &mut depth, &mut stack, &mut stack_len)?;

    while stack_len > 0 {
        let frame = stack[stack_len - 1];
        match frame {
            ContainerFrame::Compound => {
                let (&field_type, after_type) = curr.split_first().ok_or(NbtSkipError::Truncated)?;
                if field_type == 0 {
                    curr = after_type;
                    stack_len -= 1;
                    depth -= 1;
                    continue;
                }
                if field_type > 12 {
                    return Err(NbtSkipError::Malformed);
                }
                let (name_len, after_name_len) = read_be_u16(after_type)?;
                let value_rem = advance(after_name_len, name_len as usize)?;
                curr = skip_or_push_tag(
                    field_type,
                    value_rem,
                    &mut depth,
                    &mut stack,
                    &mut stack_len,
                )?;
            }
            ContainerFrame::List {
                elem_type,
                remaining,
            } => {
                if remaining == 0 {
                    stack_len -= 1;
                    depth -= 1;
                    continue;
                }
                stack[stack_len - 1] = ContainerFrame::List {
                    elem_type,
                    remaining: remaining - 1,
                };
                curr = skip_or_push_tag(elem_type, curr, &mut depth, &mut stack, &mut stack_len)?;
            }
        }
    }

    Ok(curr)
}

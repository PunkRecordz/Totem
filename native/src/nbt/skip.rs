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
pub unsafe fn advance_ptr(
    ptr: *const u8,
    end: *const u8,
    bytes: usize,
) -> Result<*const u8, NbtSkipError> {
    let remaining = (end as usize) - (ptr as usize);
    if remaining >= bytes {
        Ok(unsafe { ptr.add(bytes) })
    } else {
        Err(NbtSkipError::Truncated)
    }
}

#[inline(always)]
pub unsafe fn skip_leaf_tag(
    type_id: u8,
    ptr: *const u8,
    end: *const u8,
) -> Result<*const u8, NbtSkipError> {
    match type_id {
        0 => Ok(ptr),
        1 => unsafe { advance_ptr(ptr, end, 1) },
        2 => unsafe { advance_ptr(ptr, end, 2) },
        3 | 5 => unsafe { advance_ptr(ptr, end, 4) },
        4 | 6 => unsafe { advance_ptr(ptr, end, 8) },
        7 => {
            let payload = unsafe { advance_ptr(ptr, end, 4)? };
            let len = i32::from_be(unsafe { core::ptr::read_unaligned(ptr as *const i32) });
            if len < 0 {
                return Err(NbtSkipError::Malformed);
            }
            unsafe { advance_ptr(payload, end, len as usize) }
        }
        8 => {
            let payload = unsafe { advance_ptr(ptr, end, 2)? };
            let len = u16::from_be(unsafe { core::ptr::read_unaligned(ptr as *const u16) }) as usize;
            unsafe { advance_ptr(payload, end, len) }
        }
        11 => {
            let payload = unsafe { advance_ptr(ptr, end, 4)? };
            let len = i32::from_be(unsafe { core::ptr::read_unaligned(ptr as *const i32) });
            if len < 0 {
                return Err(NbtSkipError::Malformed);
            }
            let byte_len = (len as usize)
                .checked_mul(4)
                .ok_or(NbtSkipError::Malformed)?;
            unsafe { advance_ptr(payload, end, byte_len) }
        }
        12 => {
            let payload = unsafe { advance_ptr(ptr, end, 4)? };
            let len = i32::from_be(unsafe { core::ptr::read_unaligned(ptr as *const i32) });
            if len < 0 {
                return Err(NbtSkipError::Malformed);
            }
            let byte_len = (len as usize)
                .checked_mul(8)
                .ok_or(NbtSkipError::Malformed)?;
            unsafe { advance_ptr(payload, end, byte_len) }
        }
        _ => Err(NbtSkipError::Malformed),
    }
}

#[inline(always)]
pub unsafe fn validate_matched_tag(
    type_id: u8,
    ptr: *const u8,
    end: *const u8,
) -> Result<(), NbtSkipError> {
    match type_id {
        9 => {
            let payload = unsafe { advance_ptr(ptr, end, 5)? };
            let elem_type = unsafe { *ptr };
            let count = i32::from_be(unsafe { core::ptr::read_unaligned(ptr.add(1) as *const i32) });
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
                    unsafe { advance_ptr(payload, end, ucount)? };
                }
                2 => {
                    let bytes = ucount.checked_mul(2).ok_or(NbtSkipError::Malformed)?;
                    unsafe { advance_ptr(payload, end, bytes)? };
                }
                3 | 5 => {
                    let bytes = ucount.checked_mul(4).ok_or(NbtSkipError::Malformed)?;
                    unsafe { advance_ptr(payload, end, bytes)? };
                }
                4 | 6 => {
                    let bytes = ucount.checked_mul(8).ok_or(NbtSkipError::Malformed)?;
                    unsafe { advance_ptr(payload, end, bytes)? };
                }
                _ => {}
            }
            Ok(())
        }
        10 => {
            let _ = unsafe { advance_ptr(ptr, end, 1)? };
            let first_type = unsafe { *ptr };
            if first_type > 12 {
                return Err(NbtSkipError::Malformed);
            }
            Ok(())
        }
        _ => {
            let _ = unsafe { skip_leaf_tag(type_id, ptr, end)? };
            Ok(())
        }
    }
}

#[inline(always)]
unsafe fn skip_or_push_tag(
    type_id: u8,
    ptr: *const u8,
    end: *const u8,
    depth: &mut u32,
    stack: &mut [core::mem::MaybeUninit<ContainerFrame>; (MAX_NBT_DEPTH as usize) + 1],
    stack_len: &mut usize,
) -> Result<*const u8, NbtSkipError> {
    if *depth > MAX_NBT_DEPTH {
        return Err(NbtSkipError::DepthExceeded);
    }

    match type_id {
        9 => {
            let payload = advance_ptr(ptr, end, 5)?;
            let elem_type = unsafe { *ptr };
            let count = i32::from_be(unsafe { core::ptr::read_unaligned(ptr.add(1) as *const i32) });
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
                1 => advance_ptr(payload, end, ucount),
                2 => advance_ptr(payload, end, ucount * 2),
                3 | 5 => advance_ptr(payload, end, ucount * 4),
                4 | 6 => advance_ptr(payload, end, ucount * 8),
                _ => {
                    stack[*stack_len].write(ContainerFrame::List {
                        elem_type,
                        remaining: ucount,
                    });
                    *stack_len += 1;
                    *depth += 1;
                    Ok(payload)
                }
            }
        }
        10 => {
            stack[*stack_len].write(ContainerFrame::Compound);
            *stack_len += 1;
            *depth += 1;
            Ok(ptr)
        }
        _ => unsafe { skip_leaf_tag(type_id, ptr, end) },
    }
}

#[inline(always)]
pub unsafe fn skip_nbt_tag(
    type_id: u8,
    ptr: *const u8,
    end: *const u8,
    initial_depth: u32,
) -> Result<*const u8, NbtSkipError> {
    if initial_depth > MAX_NBT_DEPTH {
        return Err(NbtSkipError::DepthExceeded);
    }

    if type_id != 9 && type_id != 10 {
        return unsafe { skip_leaf_tag(type_id, ptr, end) };
    }

    let mut depth = initial_depth;
    let mut stack: [core::mem::MaybeUninit<ContainerFrame>; (MAX_NBT_DEPTH as usize) + 1] =
        unsafe { core::mem::MaybeUninit::uninit().assume_init() };
    let mut stack_len: usize = 0;

    let mut curr = unsafe {
        skip_or_push_tag(type_id, ptr, end, &mut depth, &mut stack, &mut stack_len)?
    };

    while stack_len > 0 {
        let frame = unsafe { *stack[stack_len - 1].as_ptr() };
        match frame {
            ContainerFrame::Compound => {
                let after_type = advance_ptr(curr, end, 1)?;
                let field_type = unsafe { *curr };
                if field_type == 0 {
                    curr = after_type;
                    stack_len -= 1;
                    depth -= 1;
                    continue;
                }
                if field_type > 12 {
                    return Err(NbtSkipError::Malformed);
                }
                let after_name_len = advance_ptr(after_type, end, 2)?;
                let name_len = u16::from_be(unsafe { core::ptr::read_unaligned(after_type as *const u16) }) as usize;
                let value_ptr = advance_ptr(after_name_len, end, name_len)?;
                curr = unsafe {
                    skip_or_push_tag(field_type, value_ptr, end, &mut depth, &mut stack, &mut stack_len)?
                };
            }
            ContainerFrame::List { elem_type, remaining } => {
                if remaining == 0 {
                    stack_len -= 1;
                    depth -= 1;
                    continue;
                }
                stack[stack_len - 1].write(ContainerFrame::List {
                    elem_type,
                    remaining: remaining - 1,
                });
                curr = unsafe {
                    skip_or_push_tag(elem_type, curr, end, &mut depth, &mut stack, &mut stack_len)?
                };
            }
        }
    }

    Ok(curr)
}

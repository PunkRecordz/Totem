#[forbid(unsafe_code)]
mod skip;

use skip::{read_be_u16, skip_nbt_tag, validate_matched_tag, NbtSkipError};

/// Calculates the exact byte size of an NBT compound tag starting at `ptr`.
/// Returns the size in bytes including the trailing Tag_End (0), -1 if truncated, -2 if malformed, or -3 if depth exceeded.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn calculate_compound_size(ptr: *const u8, max_len: u64) -> i64 {
    if ptr.is_null() || max_len == 0 {
        return 0;
    }
    let data = unsafe { core::slice::from_raw_parts(ptr, max_len as usize) };
    match skip_nbt_tag(10, data, 0) {
        Ok(after) => (data.len() - after.len()) as i64,
        Err(NbtSkipError::Truncated) => -1,
        Err(NbtSkipError::Malformed) => -2,
        Err(NbtSkipError::DepthExceeded) => -3,
    }
}

/// Calculates the exact byte size of an NBT list tag starting at `ptr`.
/// `ptr` points to the 1-byte element typeId followed by 4-byte element count.
/// Returns the size in bytes including the 5-byte header, -1 if truncated, -2 if malformed, or -3 if depth exceeded.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn calculate_list_size(ptr: *const u8, max_len: u64) -> i64 {
    if ptr.is_null() || max_len < 5 {
        return -1;
    }
    let data = unsafe { core::slice::from_raw_parts(ptr, max_len as usize) };
    match skip_nbt_tag(9, data, 0) {
        Ok(after) => (data.len() - after.len()) as i64,
        Err(NbtSkipError::Truncated) => -1,
        Err(NbtSkipError::Malformed) => -2,
        Err(NbtSkipError::DepthExceeded) => -3,
    }
}

/// Finds an entry by key inside an NBT compound tag at `ptr`.
/// Returns packed 64-bit int: bits 48..63 = typeId, bits 0..47 = valueOffset.
/// Returns -1 if not found or truncated, -2 if malformed, or -3 if depth exceeded.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn find_compound_entry(
    ptr: *const u8,
    max_len: u64,
    key_ptr: *const u8,
    key_len: u16,
) -> i64 {
    if ptr.is_null() || max_len == 0 || key_ptr.is_null() {
        return -1;
    }
    let data = unsafe { core::slice::from_raw_parts(ptr, max_len as usize) };
    let key = unsafe { core::slice::from_raw_parts(key_ptr, key_len as usize) };
    find_compound_entry_slice(data, key)
}

fn find_compound_entry_slice(data: &[u8], key: &[u8]) -> i64 {
    let mut curr = data;

    loop {
        let Some((&field_type, after_type)) = curr.split_first() else {
            return -1;
        };
        if field_type == 0 {
            return -1;
        }
        if field_type > 12 {
            return -2;
        }
        let Ok((name_len, name_and_rest)) = read_be_u16(after_type) else {
            return -1;
        };
        let name_len = name_len as usize;
        let Some((name_bytes, value_rem)) = name_and_rest.split_at_checked(name_len) else {
            return -1;
        };

        if name_bytes == key {
            match validate_matched_tag(field_type, value_rem) {
                Ok(()) => {
                    let offset = data.len() - value_rem.len();
                    return ((field_type as i64) << 48) | (offset as i64);
                }
                Err(NbtSkipError::Truncated) => return -1,
                Err(NbtSkipError::Malformed) => return -2,
                Err(NbtSkipError::DepthExceeded) => return -3,
            }
        }

        match skip_nbt_tag(field_type, value_rem, 1) {
            Ok(next) => curr = next,
            Err(NbtSkipError::Truncated) => return -1,
            Err(NbtSkipError::Malformed) => return -2,
            Err(NbtSkipError::DepthExceeded) => return -3,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_compound_size_and_entry_lookup() {
        let mut nbt = Vec::new();

        nbt.push(3u8);
        nbt.extend_from_slice(&(1u16.to_be_bytes()));
        nbt.push(b'x');
        nbt.extend_from_slice(&(42i32.to_be_bytes()));

        nbt.push(8u8);
        nbt.extend_from_slice(&(4u16.to_be_bytes()));
        nbt.extend_from_slice(b"name");
        nbt.extend_from_slice(&(5u16.to_be_bytes()));
        nbt.extend_from_slice(b"Totem");

        nbt.push(9u8);
        nbt.extend_from_slice(&(6u16.to_be_bytes()));
        nbt.extend_from_slice(b"coords");
        nbt.push(3u8);
        nbt.extend_from_slice(&(2i32.to_be_bytes()));
        nbt.extend_from_slice(&(100i32.to_be_bytes()));
        nbt.extend_from_slice(&(200i32.to_be_bytes()));

        nbt.push(0u8);

        let total_size = unsafe { calculate_compound_size(nbt.as_ptr(), nbt.len() as u64) };
        assert_eq!(total_size, nbt.len() as i64);

        let res_x = unsafe { find_compound_entry(nbt.as_ptr(), nbt.len() as u64, b"x".as_ptr(), 1) };
        assert_ne!(res_x, -1);
        let type_x = (res_x >> 48) as u8;
        let offset_x = (res_x & 0x0000FFFFFFFFFFFF) as usize;
        assert_eq!(type_x, 3);
        let val_x = i32::from_be_bytes(nbt[offset_x..offset_x + 4].try_into().unwrap());
        assert_eq!(val_x, 42);

        let res_name = unsafe { find_compound_entry(nbt.as_ptr(), nbt.len() as u64, b"name".as_ptr(), 4) };
        assert_ne!(res_name, -1);
        let type_name = (res_name >> 48) as u8;
        let offset_name = (res_name & 0x0000FFFFFFFFFFFF) as usize;
        assert_eq!(type_name, 8);
        let str_len = u16::from_be_bytes(nbt[offset_name..offset_name + 2].try_into().unwrap());
        assert_eq!(str_len, 5);

        let res_missing = unsafe { find_compound_entry(nbt.as_ptr(), nbt.len() as u64, b"missing".as_ptr(), 7) };
        assert_eq!(res_missing, -1);
    }

    #[test]
    fn test_malformed_and_deep_nbt_rejected() {
        let neg_list = vec![0x09u8, 0x00, 0x00, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0x00];
        let res = unsafe { find_compound_entry(neg_list.as_ptr(), neg_list.len() as u64, b"x".as_ptr(), 1) };
        assert_eq!(res, -2);
        let res_direct = unsafe { find_compound_entry(neg_list.as_ptr(), neg_list.len() as u64, b"".as_ptr(), 0) };
        assert_eq!(res_direct, -2);

        let mut deep_nbt = Vec::new();
        for _ in 0..600 {
            deep_nbt.push(10u8);
            deep_nbt.extend_from_slice(&(1u16.to_be_bytes()));
            deep_nbt.push(b'a');
        }
        for _ in 0..601 {
            deep_nbt.push(0u8);
        }
        let deep_size = unsafe { calculate_compound_size(deep_nbt.as_ptr(), deep_nbt.len() as u64) };
        assert_eq!(deep_size, -3);
    }

    #[test]
    fn test_calculate_list_size() {
        let mut list_bytes = Vec::new();
        list_bytes.push(10u8);
        list_bytes.extend_from_slice(&(3i32.to_be_bytes()));

        for _ in 0..3 {
            list_bytes.push(1u8);
            list_bytes.extend_from_slice(&(1u16.to_be_bytes()));
            list_bytes.push(b'b');
            list_bytes.push(1u8);
            list_bytes.push(0u8);
        }

        let calculated_size = unsafe { calculate_list_size(list_bytes.as_ptr(), list_bytes.len() as u64) };
        assert_eq!(calculated_size, list_bytes.len() as i64);
    }
}

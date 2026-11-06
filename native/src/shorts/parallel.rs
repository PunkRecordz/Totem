use rayon::prelude::*;

use super::lut::translate_shorts_lut_slice;
use super::ops::{fill_shorts_slice, replace_shorts_slice};
use super::{fill_shorts, replace_shorts, translate_shorts_lut};

const PARALLEL_THRESHOLD_ELEMENTS: usize = 65_536;
const ALIGNMENT_ELEMENTS: usize = 32;

#[unsafe(no_mangle)]
pub unsafe extern "C" fn totem_replace_shorts_parallel(
    data: *mut i16,
    length: u64,
    target: i16,
    replacement: i16,
) {
    let count = length as usize;
    if data.is_null() || count == 0 {
        return;
    }
    if !data.is_aligned() {
        unsafe { replace_shorts(data, length, target, replacement) };
        return;
    }

    let slice = unsafe { core::slice::from_raw_parts_mut(data, count) };
    for_each_short_chunk(slice, |chunk| replace_shorts_slice(chunk, target, replacement));
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn totem_fill_shorts_parallel(
    data: *mut i16,
    length: u64,
    value: i16,
) {
    let count = length as usize;
    if data.is_null() || count == 0 {
        return;
    }
    if !data.is_aligned() {
        unsafe { fill_shorts(data, length, value) };
        return;
    }

    let slice = unsafe { core::slice::from_raw_parts_mut(data, count) };
    for_each_short_chunk(slice, |chunk| fill_shorts_slice(chunk, value));
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn totem_translate_shorts_lut_parallel(
    data: *mut i16,
    length: u64,
    lookup_table: *const i16,
) {
    let count = length as usize;
    if data.is_null() || lookup_table.is_null() || count == 0 {
        return;
    }
    if !data.is_aligned() || !lookup_table.is_aligned() {
        unsafe { translate_shorts_lut(data, length, lookup_table) };
        return;
    }

    let slice = unsafe { core::slice::from_raw_parts_mut(data, count) };
    let table_reference = unsafe { &*(lookup_table as *const [i16; 65_536]) };
    for_each_short_chunk(slice, |chunk| translate_shorts_lut_slice(chunk, table_reference));
}

#[inline]
fn for_each_short_chunk(slice: &mut [i16], operation: impl Fn(&mut [i16]) + Sync + Send) {
    let threads = rayon::current_num_threads().max(1);
    if slice.len() < PARALLEL_THRESHOLD_ELEMENTS || threads <= 1 {
        operation(slice);
        return;
    }

    let raw_chunk_size = slice.len().div_ceil(threads);
    let chunk_size = (raw_chunk_size.div_ceil(ALIGNMENT_ELEMENTS) * ALIGNMENT_ELEMENTS).max(ALIGNMENT_ELEMENTS);
    slice.par_chunks_mut(chunk_size).for_each(operation);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parallel_short_ops_aligned_and_unaligned() {
        let mut data = vec![0i16; 140_003];
        unsafe { totem_fill_shorts_parallel(data.as_mut_ptr(), data.len() as u64, 7) };
        assert!(data.iter().all(|&value| value == 7));

        data[10] = 3;
        data[130_000] = 3;
        unsafe { totem_replace_shorts_parallel(data.as_mut_ptr(), data.len() as u64, 3, 42) };
        assert_eq!(data[10], 42);
        assert_eq!(data[130_000], 42);

        let mut lookup_table = vec![0i16; 65_536];
        lookup_table[7] = 19;
        lookup_table[42] = 99;
        unsafe { totem_translate_shorts_lut_parallel(data.as_mut_ptr(), data.len() as u64, lookup_table.as_ptr()) };
        assert_eq!(data[0], 19);
        assert_eq!(data[10], 99);
        assert_eq!(data[130_000], 99);

        let mut unaligned_bytes = vec![0u8; 1 + 64 * 2];
        let unaligned_ptr = unsafe { unaligned_bytes.as_mut_ptr().add(1) as *mut i16 };
        unsafe {
            totem_fill_shorts_parallel(unaligned_ptr, 64, 5);
            totem_replace_shorts_parallel(unaligned_ptr, 64, 5, 42);
            totem_translate_shorts_lut_parallel(unaligned_ptr, 64, lookup_table.as_ptr());
            assert_eq!(unaligned_ptr.read_unaligned(), 99);
        }
    }
}

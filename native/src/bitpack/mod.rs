#[forbid(unsafe_code)]
pub mod element;
#[forbid(unsafe_code)]
pub mod pack;
#[forbid(unsafe_code)]
pub mod unpack;

use pack::pack_elements_dispatch;
use unpack::unpack_elements_dispatch;

#[unsafe(no_mangle)]
pub unsafe extern "C" fn unpack_longs_to_shorts(
    source_longs: *const u64,
    longs_count: usize,
    destination_shorts: *mut u16,
    element_count: usize,
    bits_per_entry: u32,
    precise: bool,
    big_endian_source: bool,
) -> usize {
    if source_longs.is_null()
        || destination_shorts.is_null()
        || element_count == 0
        || bits_per_entry == 0
        || bits_per_entry > 16
    {
        return 0;
    }

    let source = unsafe { core::slice::from_raw_parts(source_longs, longs_count) };
    let destination = unsafe { core::slice::from_raw_parts_mut(destination_shorts, element_count) };

    if big_endian_source {
        unpack_elements_dispatch::<u16, true>(source, destination, bits_per_entry as usize, precise)
    } else {
        unpack_elements_dispatch::<u16, false>(
            source,
            destination,
            bits_per_entry as usize,
            precise,
        )
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn pack_shorts_to_longs(
    source_shorts: *const u16,
    element_count: usize,
    destination_longs: *mut u64,
    longs_count: usize,
    bits_per_entry: u32,
    precise: bool,
    big_endian_destination: bool,
) -> usize {
    if source_shorts.is_null()
        || destination_longs.is_null()
        || element_count == 0
        || longs_count == 0
        || bits_per_entry == 0
        || bits_per_entry > 16
    {
        return 0;
    }

    let source = unsafe { core::slice::from_raw_parts(source_shorts, element_count) };
    let destination = unsafe { core::slice::from_raw_parts_mut(destination_longs, longs_count) };

    if big_endian_destination {
        pack_elements_dispatch::<u16, true>(source, destination, bits_per_entry as usize, precise)
    } else {
        pack_elements_dispatch::<u16, false>(source, destination, bits_per_entry as usize, precise)
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn unpack_longs_to_longs(
    source_longs: *const u64,
    longs_count: usize,
    destination_longs: *mut u64,
    element_count: usize,
    bits_per_entry: u32,
    precise: bool,
    big_endian_source: bool,
) -> usize {
    if source_longs.is_null()
        || destination_longs.is_null()
        || element_count == 0
        || bits_per_entry == 0
        || bits_per_entry > 64
    {
        return 0;
    }

    let source = unsafe { core::slice::from_raw_parts(source_longs, longs_count) };
    let destination = unsafe { core::slice::from_raw_parts_mut(destination_longs, element_count) };

    if big_endian_source {
        unpack_elements_dispatch::<u64, true>(source, destination, bits_per_entry as usize, precise)
    } else {
        unpack_elements_dispatch::<u64, false>(
            source,
            destination,
            bits_per_entry as usize,
            precise,
        )
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn pack_longs_to_longs(
    source_longs: *const u64,
    element_count: usize,
    destination_longs: *mut u64,
    longs_count: usize,
    bits_per_entry: u32,
    precise: bool,
    big_endian_destination: bool,
) -> usize {
    if source_longs.is_null()
        || destination_longs.is_null()
        || element_count == 0
        || longs_count == 0
        || bits_per_entry == 0
        || bits_per_entry > 64
    {
        return 0;
    }

    let source = unsafe { core::slice::from_raw_parts(source_longs, element_count) };
    let destination = unsafe { core::slice::from_raw_parts_mut(destination_longs, longs_count) };

    if big_endian_destination {
        pack_elements_dispatch::<u64, true>(source, destination, bits_per_entry as usize, precise)
    } else {
        pack_elements_dispatch::<u64, false>(source, destination, bits_per_entry as usize, precise)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_pack_unpack_shorts_all_bits_and_modes() {
        let count = 4096usize;
        for bits in 1..=16u32 {
            let max_val = if bits == 16 { 65535u32 } else { (1u32 << bits) - 1 };
            let input: Vec<u16> = (0..count)
                .map(|index| ((index as u32 * 37 + 13) & max_val) as u16)
                .collect();

            for &precise in &[false, true] {
                for &big_endian in &[false, true] {
                    let longs_count = if precise {
                        (count * bits as usize + 63) / 64
                    } else {
                        let per_long = 64 / bits as usize;
                        (count + per_long - 1) / per_long
                    };

                    let mut packed = vec![0u64; longs_count];
                    let mut unpacked = vec![0u16; count];

                    unsafe {
                        let written = pack_shorts_to_longs(
                            input.as_ptr(),
                            count,
                            packed.as_mut_ptr(),
                            longs_count,
                            bits,
                            precise,
                            big_endian,
                        );
                        assert_eq!(written, longs_count);

                        let read_count = unpack_longs_to_shorts(
                            packed.as_ptr(),
                            longs_count,
                            unpacked.as_mut_ptr(),
                            count,
                            bits,
                            precise,
                            big_endian,
                        );
                        assert_eq!(read_count, count);
                    }

                    assert_eq!(input, unpacked, "Mismatch at bits={bits}, precise={precise}, big_endian={big_endian}");
                }
            }
        }
    }

    #[test]
    fn test_pack_unpack_longs_all_bits_and_modes() {
        let count = 4096usize;
        for bits in 1..=20u32 {
            let mask = (1u64 << bits) - 1;
            let input: Vec<u64> = (0..count)
                .map(|index| (index as u64 * 131 + 7) & mask)
                .collect();

            for &precise in &[false, true] {
                let longs_count = if precise {
                    (count * bits as usize + 63) / 64
                } else {
                    let per_long = 64 / bits as usize;
                    (count + per_long - 1) / per_long
                };

                let mut packed = vec![0u64; longs_count];
                let mut unpacked = vec![0u64; count];

                unsafe {
                    pack_longs_to_longs(input.as_ptr(), count, packed.as_mut_ptr(), longs_count, bits, precise, false);
                    unpack_longs_to_longs(packed.as_ptr(), longs_count, unpacked.as_mut_ptr(), count, bits, precise, false);
                }

                assert_eq!(input, unpacked, "Longs mismatch at bits={bits}, precise={precise}");
            }
        }
    }
}

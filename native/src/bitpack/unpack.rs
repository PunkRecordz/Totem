use super::element::{decode_u64, UnpackElement};

#[inline(always)]
unsafe fn unpack_padded_const<T: UnpackElement, const BITS: usize, const BIG_ENDIAN: bool>(
    source_longs: *const u64,
    longs_count: usize,
    destination: *mut T,
    element_count: usize,
) -> usize {
    let entries_per_long = 64 / BITS;
    let mask: u64 = if BITS == 64 { u64::MAX } else { (1u64 << BITS) - 1 };
    let unpacked_size = core::cmp::min(longs_count.saturating_mul(entries_per_long), element_count);
    let full_words = unpacked_size / entries_per_long;
    let remainder = unpacked_size % entries_per_long;

    for word_index in 0..full_words {
        let word = decode_u64::<BIG_ENDIAN>(unsafe { source_longs.add(word_index).read_unaligned() });
        let out_base = unsafe { destination.add(word_index * entries_per_long) };

        for entry_index in 0..entries_per_long {
            let value = (word >> (entry_index * BITS)) & mask;
            unsafe { out_base.add(entry_index).write_unaligned(T::from_u64(value)) };
        }
    }

    if remainder > 0 {
        let word = decode_u64::<BIG_ENDIAN>(unsafe { source_longs.add(full_words).read_unaligned() });
        let out_base = unsafe { destination.add(full_words * entries_per_long) };

        for entry_index in 0..remainder {
            let value = (word >> (entry_index * BITS)) & mask;
            unsafe { out_base.add(entry_index).write_unaligned(T::from_u64(value)) };
        }
    }

    for index in unpacked_size..element_count {
        unsafe { destination.add(index).write_unaligned(T::ZERO) };
    }

    unpacked_size
}

#[inline(always)]
unsafe fn unpack_spanning_const<T: UnpackElement, const BITS: usize, const BIG_ENDIAN: bool>(
    source_longs: *const u64,
    longs_count: usize,
    destination: *mut T,
    element_count: usize,
) -> usize {
    if 64 % BITS == 0 {
        return unsafe {
            unpack_padded_const::<T, BITS, BIG_ENDIAN>(source_longs, longs_count, destination, element_count)
        };
    }

    let mask: u64 = (1u64 << BITS) - 1;
    let min_in_word = (65 - BITS) / BITS;
    let mut out_index = 0;
    let mut word_index = 0;
    let mut bit_offset = 0;
    let mut current_word = if longs_count > 0 {
        word_index = 1;
        decode_u64::<BIG_ENDIAN>(unsafe { source_longs.read_unaligned() })
    } else {
        0
    };

    while out_index + min_in_word + 2 <= element_count && word_index < longs_count {
        let shifted = current_word >> bit_offset;
        let out_base = unsafe { destination.add(out_index) };

        for step in 0..min_in_word {
            let value = (shifted >> (step * BITS)) & mask;
            unsafe { out_base.add(step).write_unaligned(T::from_u64(value)) };
        }

        out_index += min_in_word;
        bit_offset += min_in_word * BITS;

        if bit_offset + BITS <= 64 {
            let value = (current_word >> bit_offset) & mask;
            unsafe { destination.add(out_index).write_unaligned(T::from_u64(value)) };
            out_index += 1;
            bit_offset += BITS;
        }

        let next_word = decode_u64::<BIG_ENDIAN>(unsafe { source_longs.add(word_index).read_unaligned() });
        word_index += 1;

        if bit_offset == 64 {
            current_word = next_word;
            bit_offset = 0;
        } else {
            let value = ((current_word >> bit_offset) | (next_word << (64 - bit_offset))) & mask;
            unsafe { destination.add(out_index).write_unaligned(T::from_u64(value)) };
            out_index += 1;
            current_word = next_word;
            bit_offset = (bit_offset + BITS) - 64;
        }
    }

    unsafe {
        unpack_spanning_tail::<T, BIG_ENDIAN>(
            source_longs,
            longs_count,
            destination,
            element_count,
            BITS,
            mask,
            out_index,
            word_index,
            current_word,
            bit_offset,
        )
    }
}

#[inline(always)]
unsafe fn unpack_spanning_tail<T: UnpackElement, const BIG_ENDIAN: bool>(
    source_longs: *const u64,
    longs_count: usize,
    destination: *mut T,
    element_count: usize,
    bits_per_entry: usize,
    mask: u64,
    mut out_index: usize,
    mut word_index: usize,
    mut current_word: u64,
    mut bit_offset: usize,
) -> usize {
    while out_index < element_count {
        let value: u64;
        if bit_offset + bits_per_entry <= 64 {
            value = (current_word >> bit_offset) & mask;
            bit_offset += bits_per_entry;
            if bit_offset == 64 {
                bit_offset = 0;
                current_word = if word_index < longs_count {
                    let next = decode_u64::<BIG_ENDIAN>(unsafe { source_longs.add(word_index).read_unaligned() });
                    word_index += 1;
                    next
                } else {
                    0
                };
            }
        } else {
            let next_word = if word_index < longs_count {
                let next = decode_u64::<BIG_ENDIAN>(unsafe { source_longs.add(word_index).read_unaligned() });
                word_index += 1;
                next
            } else {
                0
            };
            value = ((current_word >> bit_offset) | (next_word << (64 - bit_offset))) & mask;
            current_word = next_word;
            bit_offset = (bit_offset + bits_per_entry) - 64;
        }

        unsafe { destination.add(out_index).write_unaligned(T::from_u64(value)) };
        out_index += 1;
    }

    element_count
}

#[inline(always)]
unsafe fn unpack_runtime<T: UnpackElement, const BIG_ENDIAN: bool>(
    source_longs: *const u64,
    longs_count: usize,
    destination: *mut T,
    element_count: usize,
    bits_per_entry: usize,
    precise: bool,
) -> usize {
    let mask: u64 = if bits_per_entry >= 64 { u64::MAX } else { (1u64 << bits_per_entry) - 1 };

    if !precise {
        let entries_per_long = 64 / bits_per_entry;
        let unpacked_size = core::cmp::min(longs_count.saturating_mul(entries_per_long), element_count);
        let mut out_index = 0;

        for word_index in 0..longs_count {
            let word = decode_u64::<BIG_ENDIAN>(unsafe { source_longs.add(word_index).read_unaligned() });
            let mut bit_index = 0;

            for _ in 0..entries_per_long {
                if out_index >= unpacked_size {
                    break;
                }
                unsafe { destination.add(out_index).write_unaligned(T::from_u64((word >> bit_index) & mask)) };
                bit_index += bits_per_entry;
                out_index += 1;
            }
        }

        for index in unpacked_size..element_count {
            unsafe { destination.add(index).write_unaligned(T::ZERO) };
        }

        return unpacked_size;
    }

    let (word_index, current_word) = if longs_count > 0 {
        (1, decode_u64::<BIG_ENDIAN>(unsafe { source_longs.read_unaligned() }))
    } else {
        (0, 0)
    };

    unsafe {
        unpack_spanning_tail::<T, BIG_ENDIAN>(
            source_longs,
            longs_count,
            destination,
            element_count,
            bits_per_entry,
            mask,
            0,
            word_index,
            current_word,
            0,
        )
    }
}

pub unsafe fn unpack_elements_dispatch<T: UnpackElement, const BIG_ENDIAN: bool>(
    source_longs: *const u64,
    longs_count: usize,
    destination: *mut T,
    element_count: usize,
    bits_per_entry: usize,
    precise: bool,
) -> usize {
    macro_rules! dispatch_bits {
        ($($b:literal),*) => {
            match bits_per_entry {
                $(
                    $b => if precise {
                        unsafe { unpack_spanning_const::<T, $b, BIG_ENDIAN>(source_longs, longs_count, destination, element_count) }
                    } else {
                        unsafe { unpack_padded_const::<T, $b, BIG_ENDIAN>(source_longs, longs_count, destination, element_count) }
                    },
                )*
                _ => unsafe { unpack_runtime::<T, BIG_ENDIAN>(source_longs, longs_count, destination, element_count, bits_per_entry, precise) },
            }
        };
    }

    dispatch_bits!(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
}

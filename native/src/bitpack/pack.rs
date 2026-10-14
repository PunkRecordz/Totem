use super::element::{encode_u64, PackElement};

#[inline(always)]
unsafe fn pack_padded_const<T: PackElement, const BITS: usize, const BIG_ENDIAN: bool>(
    source: *const T,
    element_count: usize,
    destination_longs: *mut u64,
    longs_count: usize,
) -> usize {
    let entries_per_long = 64 / BITS;
    let mask: u64 = if BITS == 64 { u64::MAX } else { (1u64 << BITS) - 1 };
    let full_words = core::cmp::min(element_count / entries_per_long, longs_count);
    let remainder = element_count.saturating_sub(full_words * entries_per_long);

    for word_index in 0..full_words {
        let in_base = unsafe { source.add(word_index * entries_per_long) };
        let mut word: u64 = 0;

        for entry_index in 0..entries_per_long {
            let value = unsafe { in_base.add(entry_index).read_unaligned() }.to_u64() & mask;
            word |= value << (entry_index * BITS);
        }

        unsafe { destination_longs.add(word_index).write_unaligned(encode_u64::<BIG_ENDIAN>(word)) };
    }

    let mut written_words = full_words;

    if remainder > 0 && written_words < longs_count {
        let count = core::cmp::min(remainder, entries_per_long);
        let in_base = unsafe { source.add(full_words * entries_per_long) };
        let mut word: u64 = 0;

        for entry_index in 0..count {
            let value = unsafe { in_base.add(entry_index).read_unaligned() }.to_u64() & mask;
            word |= value << (entry_index * BITS);
        }

        unsafe { destination_longs.add(written_words).write_unaligned(encode_u64::<BIG_ENDIAN>(word)) };
        written_words += 1;
    }

    written_words
}

#[inline(always)]
unsafe fn pack_spanning_const<T: PackElement, const BITS: usize, const BIG_ENDIAN: bool>(
    source: *const T,
    element_count: usize,
    destination_longs: *mut u64,
    longs_count: usize,
) -> usize {
    if 64 % BITS == 0 {
        return unsafe {
            pack_padded_const::<T, BITS, BIG_ENDIAN>(source, element_count, destination_longs, longs_count)
        };
    }

    let mask: u64 = (1u64 << BITS) - 1;
    let min_in_word = (65 - BITS) / BITS;
    let mut in_index = 0;
    let mut word_index = 0;
    let mut current_word: u64 = 0;
    let mut bit_offset = 0;

    while in_index + min_in_word + 2 <= element_count && word_index < longs_count {
        let in_base = unsafe { source.add(in_index) };
        let mut chunk: u64 = 0;

        for step in 0..min_in_word {
            let value = unsafe { in_base.add(step).read_unaligned() }.to_u64() & mask;
            chunk |= value << (step * BITS);
        }

        current_word |= chunk << bit_offset;
        in_index += min_in_word;
        bit_offset += min_in_word * BITS;

        if bit_offset + BITS <= 64 {
            let value = unsafe { source.add(in_index).read_unaligned() }.to_u64() & mask;
            current_word |= value << bit_offset;
            in_index += 1;
            bit_offset += BITS;
        }

        if bit_offset == 64 {
            unsafe { destination_longs.add(word_index).write_unaligned(encode_u64::<BIG_ENDIAN>(current_word)) };
            word_index += 1;
            current_word = 0;
            bit_offset = 0;
        } else {
            let value = unsafe { source.add(in_index).read_unaligned() }.to_u64() & mask;
            in_index += 1;
            current_word |= value << bit_offset;
            unsafe { destination_longs.add(word_index).write_unaligned(encode_u64::<BIG_ENDIAN>(current_word)) };
            word_index += 1;
            bit_offset = (bit_offset + BITS) - 64;
            current_word = value >> (BITS - bit_offset);
        }
    }

    unsafe {
        pack_spanning_tail::<T, BIG_ENDIAN>(
            source,
            element_count,
            destination_longs,
            longs_count,
            BITS,
            mask,
            in_index,
            word_index,
            current_word,
            bit_offset,
        )
    }
}

#[inline(always)]
unsafe fn pack_spanning_tail<T: PackElement, const BIG_ENDIAN: bool>(
    source: *const T,
    element_count: usize,
    destination_longs: *mut u64,
    longs_count: usize,
    bits_per_entry: usize,
    mask: u64,
    mut in_index: usize,
    mut word_index: usize,
    mut current_word: u64,
    mut bit_offset: usize,
) -> usize {
    while in_index < element_count {
        let value = unsafe { source.add(in_index).read_unaligned() }.to_u64() & mask;
        current_word |= value << bit_offset;
        bit_offset += bits_per_entry;
        in_index += 1;

        if bit_offset >= 64 {
            if word_index < longs_count {
                unsafe {
                    destination_longs
                        .add(word_index)
                        .write_unaligned(encode_u64::<BIG_ENDIAN>(current_word));
                }
                word_index += 1;
            }
            bit_offset -= 64;
            current_word = if bit_offset > 0 {
                value >> (bits_per_entry - bit_offset)
            } else {
                0
            };
        }
    }

    if bit_offset > 0 && word_index < longs_count {
        unsafe {
            destination_longs
                .add(word_index)
                .write_unaligned(encode_u64::<BIG_ENDIAN>(current_word));
        }
        word_index += 1;
    }

    word_index
}

#[inline(always)]
unsafe fn pack_runtime<T: PackElement, const BIG_ENDIAN: bool>(
    source: *const T,
    element_count: usize,
    destination_longs: *mut u64,
    longs_count: usize,
    bits_per_entry: usize,
    precise: bool,
) -> usize {
    let mask: u64 = if bits_per_entry >= 64 { u64::MAX } else { (1u64 << bits_per_entry) - 1 };

    if !precise {
        let entries_per_long = 64 / bits_per_entry;
        let mut in_index = 0;

        for word_index in 0..longs_count {
            let mut word: u64 = 0;
            let mut bit_index = 0;

            for _ in 0..entries_per_long {
                if in_index >= element_count {
                    break;
                }
                let value = unsafe { source.add(in_index).read_unaligned() }.to_u64() & mask;
                word |= value << bit_index;
                bit_index += bits_per_entry;
                in_index += 1;
            }

            unsafe {
                destination_longs
                    .add(word_index)
                    .write_unaligned(encode_u64::<BIG_ENDIAN>(word));
            }
        }

        return longs_count;
    }

    unsafe {
        pack_spanning_tail::<T, BIG_ENDIAN>(
            source,
            element_count,
            destination_longs,
            longs_count,
            bits_per_entry,
            mask,
            0,
            0,
            0,
            0,
        )
    }
}

pub unsafe fn pack_elements_dispatch<T: PackElement, const BIG_ENDIAN: bool>(
    source: *const T,
    element_count: usize,
    destination_longs: *mut u64,
    longs_count: usize,
    bits_per_entry: usize,
    precise: bool,
) -> usize {
    macro_rules! dispatch_bits {
        ($($b:literal),*) => {
            match bits_per_entry {
                $(
                    $b => if precise {
                        unsafe { pack_spanning_const::<T, $b, BIG_ENDIAN>(source, element_count, destination_longs, longs_count) }
                    } else {
                        unsafe { pack_padded_const::<T, $b, BIG_ENDIAN>(source, element_count, destination_longs, longs_count) }
                    },
                )*
                _ => unsafe { pack_runtime::<T, BIG_ENDIAN>(source, element_count, destination_longs, longs_count, bits_per_entry, precise) },
            }
        };
    }

    dispatch_bits!(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
}

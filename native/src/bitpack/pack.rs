use super::element::{encode_u64, PackElement};

#[inline(always)]
fn pack_padded_const<T: PackElement, const BITS: usize, const BIG_ENDIAN: bool>(
    source: &[T],
    destination_longs: &mut [u64],
) -> usize {
    let entries_per_long = 64 / BITS;
    let mask: u64 = if BITS == 64 { u64::MAX } else { (1u64 << BITS) - 1 };
    let mut chunks = source.chunks_exact(entries_per_long);
    let mut written_words = 0;

    for (chunk, dest_word) in (&mut chunks).zip(destination_longs.iter_mut()) {
        let mut word: u64 = 0;

        for (entry_index, &element) in chunk.iter().enumerate() {
            let value = element.to_u64() & mask;
            word |= value << (entry_index * BITS);
        }

        *dest_word = encode_u64::<BIG_ENDIAN>(word);
        written_words += 1;
    }

    let remainder = chunks.remainder();
    if !remainder.is_empty() && written_words < destination_longs.len() {
        let mut word: u64 = 0;

        for (entry_index, &element) in remainder.iter().enumerate() {
            let value = element.to_u64() & mask;
            word |= value << (entry_index * BITS);
        }

        destination_longs[written_words] = encode_u64::<BIG_ENDIAN>(word);
        written_words += 1;
    }

    written_words
}

#[inline(always)]
fn pack_spanning_const<T: PackElement, const BITS: usize, const BIG_ENDIAN: bool>(
    source: &[T],
    destination_longs: &mut [u64],
) -> usize {
    if 64 % BITS == 0 {
        return pack_padded_const::<T, BITS, BIG_ENDIAN>(source, destination_longs);
    }

    let mask: u64 = (1u64 << BITS) - 1;
    let min_in_word = (65 - BITS) / BITS;
    let mut in_index = 0;
    let mut word_index = 0;
    let mut current_word: u64 = 0;
    let mut bit_offset = 0;

    while in_index + min_in_word + 2 <= source.len() && word_index < destination_longs.len() {
        let window = &source[in_index..in_index + min_in_word + 2];
        let mut chunk: u64 = 0;

        for (step, &element) in window[..min_in_word].iter().enumerate() {
            let value = element.to_u64() & mask;
            chunk |= value << (step * BITS);
        }

        current_word |= chunk << bit_offset;
        let mut local_index = min_in_word;
        bit_offset += min_in_word * BITS;

        if bit_offset + BITS <= 64 {
            let value = window[local_index].to_u64() & mask;
            current_word |= value << bit_offset;
            local_index += 1;
            bit_offset += BITS;
        }

        if bit_offset == 64 {
            destination_longs[word_index] = encode_u64::<BIG_ENDIAN>(current_word);
            word_index += 1;
            current_word = 0;
            bit_offset = 0;
        } else {
            let value = window[local_index].to_u64() & mask;
            local_index += 1;
            current_word |= value << bit_offset;
            destination_longs[word_index] = encode_u64::<BIG_ENDIAN>(current_word);
            word_index += 1;
            bit_offset = (bit_offset + BITS) - 64;
            current_word = value >> (BITS - bit_offset);
        }

        in_index += local_index;
    }

    pack_spanning_tail::<T, BIG_ENDIAN>(
        &source[in_index..],
        destination_longs,
        BITS,
        mask,
        word_index,
        current_word,
        bit_offset,
    )
}

#[inline(always)]
fn pack_spanning_tail<T: PackElement, const BIG_ENDIAN: bool>(
    remaining_source: &[T],
    destination_longs: &mut [u64],
    bits_per_entry: usize,
    mask: u64,
    mut word_index: usize,
    mut current_word: u64,
    mut bit_offset: usize,
) -> usize {
    for &element in remaining_source {
        let value = element.to_u64() & mask;
        current_word |= value << bit_offset;
        bit_offset += bits_per_entry;

        if bit_offset >= 64 {
            if let Some(slot) = destination_longs.get_mut(word_index) {
                *slot = encode_u64::<BIG_ENDIAN>(current_word);
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

    if bit_offset > 0 {
        if let Some(slot) = destination_longs.get_mut(word_index) {
            *slot = encode_u64::<BIG_ENDIAN>(current_word);
            word_index += 1;
        }
    }

    word_index
}

#[inline(always)]
fn pack_runtime<T: PackElement, const BIG_ENDIAN: bool>(
    source: &[T],
    destination_longs: &mut [u64],
    bits_per_entry: usize,
    precise: bool,
) -> usize {
    let mask: u64 = if bits_per_entry >= 64 {
        u64::MAX
    } else {
        (1u64 << bits_per_entry) - 1
    };

    if !precise {
        let entries_per_long = 64 / bits_per_entry;
        let mut source_iter = source.iter();

        for dest_word in destination_longs.iter_mut() {
            let mut word: u64 = 0;
            let mut bit_index = 0;

            for _ in 0..entries_per_long {
                let Some(&element) = source_iter.next() else {
                    break;
                };
                let value = element.to_u64() & mask;
                word |= value << bit_index;
                bit_index += bits_per_entry;
            }

            *dest_word = encode_u64::<BIG_ENDIAN>(word);
        }

        return destination_longs.len();
    }

    pack_spanning_tail::<T, BIG_ENDIAN>(source, destination_longs, bits_per_entry, mask, 0, 0, 0)
}

pub fn pack_elements_dispatch<T: PackElement, const BIG_ENDIAN: bool>(
    source: &[T],
    destination_longs: &mut [u64],
    bits_per_entry: usize,
    precise: bool,
) -> usize {
    macro_rules! dispatch_bits {
        ($($b:literal),*) => {
            match bits_per_entry {
                $(
                    $b => if precise {
                        pack_spanning_const::<T, $b, BIG_ENDIAN>(source, destination_longs)
                    } else {
                        pack_padded_const::<T, $b, BIG_ENDIAN>(source, destination_longs)
                    },
                )*
                _ => pack_runtime::<T, BIG_ENDIAN>(source, destination_longs, bits_per_entry, precise),
            }
        };
    }

    dispatch_bits!(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
}

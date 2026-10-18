use super::element::{decode_u64, UnpackElement};

#[inline(always)]
fn unpack_padded_const<T: UnpackElement, const BITS: usize, const BIG_ENDIAN: bool>(
    source_longs: &[u64],
    destination: &mut [T],
) -> usize {
    let entries_per_long = 64 / BITS;
    let mask: u64 = if BITS == 64 { u64::MAX } else { (1u64 << BITS) - 1 };
    let unpacked_size = core::cmp::min(
        source_longs.len().saturating_mul(entries_per_long),
        destination.len(),
    );

    let mut out_chunks = destination[..unpacked_size].chunks_exact_mut(entries_per_long);
    let mut word_iter = source_longs.iter();

    for (out_chunk, &raw_word) in (&mut out_chunks).zip(&mut word_iter) {
        let word = decode_u64::<BIG_ENDIAN>(raw_word);

        for (entry_index, slot) in out_chunk.iter_mut().enumerate() {
            let value = (word >> (entry_index * BITS)) & mask;
            *slot = T::from_u64(value);
        }
    }

    let remainder = out_chunks.into_remainder();
    if !remainder.is_empty() {
        if let Some(&raw_word) = word_iter.next() {
            let word = decode_u64::<BIG_ENDIAN>(raw_word);

            for (entry_index, slot) in remainder.iter_mut().enumerate() {
                let value = (word >> (entry_index * BITS)) & mask;
                *slot = T::from_u64(value);
            }
        }
    }

    destination[unpacked_size..].fill(T::ZERO);
    unpacked_size
}

#[inline(always)]
fn unpack_spanning_const<T: UnpackElement, const BITS: usize, const BIG_ENDIAN: bool>(
    source_longs: &[u64],
    destination: &mut [T],
) -> usize {
    if 64 % BITS == 0 {
        return unpack_padded_const::<T, BITS, BIG_ENDIAN>(source_longs, destination);
    }

    let mask: u64 = (1u64 << BITS) - 1;
    let min_in_word = (65 - BITS) / BITS;
    let mut out_index = 0;
    let mut word_index = 0;
    let mut bit_offset = 0;
    let mut current_word = if let Some(&first) = source_longs.first() {
        word_index = 1;
        decode_u64::<BIG_ENDIAN>(first)
    } else {
        0
    };

    while out_index + min_in_word + 2 <= destination.len() && word_index < source_longs.len() {
        let shifted = current_word >> bit_offset;
        let out_window = &mut destination[out_index..out_index + min_in_word + 2];

        for (step, slot) in out_window[..min_in_word].iter_mut().enumerate() {
            let value = (shifted >> (step * BITS)) & mask;
            *slot = T::from_u64(value);
        }

        let mut local_out = min_in_word;
        bit_offset += min_in_word * BITS;

        if bit_offset + BITS <= 64 {
            let value = (current_word >> bit_offset) & mask;
            out_window[local_out] = T::from_u64(value);
            local_out += 1;
            bit_offset += BITS;
        }

        let next_word = decode_u64::<BIG_ENDIAN>(source_longs[word_index]);
        word_index += 1;

        if bit_offset == 64 {
            current_word = next_word;
            bit_offset = 0;
        } else {
            let value = ((current_word >> bit_offset) | (next_word << (64 - bit_offset))) & mask;
            out_window[local_out] = T::from_u64(value);
            local_out += 1;
            current_word = next_word;
            bit_offset = (bit_offset + BITS) - 64;
        }

        out_index += local_out;
    }

    unpack_spanning_tail::<T, BIG_ENDIAN>(
        &source_longs[word_index..],
        &mut destination[out_index..],
        BITS,
        mask,
        current_word,
        bit_offset,
    );

    destination.len()
}

#[inline(always)]
fn unpack_spanning_tail<T: UnpackElement, const BIG_ENDIAN: bool>(
    remaining_longs: &[u64],
    remaining_destination: &mut [T],
    bits_per_entry: usize,
    mask: u64,
    mut current_word: u64,
    mut bit_offset: usize,
) {
    let mut word_iter = remaining_longs.iter();

    for slot in remaining_destination.iter_mut() {
        let value: u64;
        if bit_offset + bits_per_entry <= 64 {
            value = (current_word >> bit_offset) & mask;
            bit_offset += bits_per_entry;
            if bit_offset == 64 {
                bit_offset = 0;
                current_word = word_iter
                    .next()
                    .map_or(0, |&raw| decode_u64::<BIG_ENDIAN>(raw));
            }
        } else {
            let next_word = word_iter
                .next()
                .map_or(0, |&raw| decode_u64::<BIG_ENDIAN>(raw));
            value = ((current_word >> bit_offset) | (next_word << (64 - bit_offset))) & mask;
            current_word = next_word;
            bit_offset = (bit_offset + bits_per_entry) - 64;
        }

        *slot = T::from_u64(value);
    }
}

#[inline(always)]
fn unpack_runtime<T: UnpackElement, const BIG_ENDIAN: bool>(
    source_longs: &[u64],
    destination: &mut [T],
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
        let unpacked_size = core::cmp::min(
            source_longs.len().saturating_mul(entries_per_long),
            destination.len(),
        );
        let mut out_iter = destination[..unpacked_size].iter_mut();

        for &raw_word in source_longs {
            let word = decode_u64::<BIG_ENDIAN>(raw_word);
            let mut bit_index = 0;

            for _ in 0..entries_per_long {
                let Some(slot) = out_iter.next() else {
                    break;
                };
                *slot = T::from_u64((word >> bit_index) & mask);
                bit_index += bits_per_entry;
            }
        }

        destination[unpacked_size..].fill(T::ZERO);
        return unpacked_size;
    }

    let (remaining_longs, current_word) = if let Some((&first, rest)) = source_longs.split_first()
    {
        (rest, decode_u64::<BIG_ENDIAN>(first))
    } else {
        (&[][..], 0)
    };

    unpack_spanning_tail::<T, BIG_ENDIAN>(
        remaining_longs,
        destination,
        bits_per_entry,
        mask,
        current_word,
        0,
    );

    destination.len()
}

pub fn unpack_elements_dispatch<T: UnpackElement, const BIG_ENDIAN: bool>(
    source_longs: &[u64],
    destination: &mut [T],
    bits_per_entry: usize,
    precise: bool,
) -> usize {
    macro_rules! dispatch_bits {
        ($($b:literal),*) => {
            match bits_per_entry {
                $(
                    $b => if precise {
                        unpack_spanning_const::<T, $b, BIG_ENDIAN>(source_longs, destination)
                    } else {
                        unpack_padded_const::<T, $b, BIG_ENDIAN>(source_longs, destination)
                    },
                )*
                _ => unpack_runtime::<T, BIG_ENDIAN>(source_longs, destination, bits_per_entry, precise),
            }
        };
    }

    dispatch_bits!(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
}

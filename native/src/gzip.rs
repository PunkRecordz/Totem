/// Scans a multi-member GZIP stream for individual member start offsets using AVX2 SIMD.
/// Writes discovered offsets into `out_offsets` (up to `max_out`) and returns the member count.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn scan_gzip_members(
    data: *const u8,
    len: u64,
    out_offsets: *mut i64,
    max_out: u32,
) -> i32 {
    let len = len as usize;
    let max_out = max_out as usize;
    if data.is_null() || len < 18 || out_offsets.is_null() || max_out == 0 {
        return 0;
    }

    let slice = core::slice::from_raw_parts(data, len);
    let mut count = 0;
    let mut offset = 0;

    #[cfg(target_arch = "x86_64")]
    {
        use core::arch::x86_64::*;
        let target = _mm256_set1_epi8(0x1F_i8);

        while offset + 32 + 18 <= len && count < max_out {
            let chunk = _mm256_loadu_si256(slice.as_ptr().add(offset) as *const __m256i);
            let cmp = _mm256_cmpeq_epi8(chunk, target);
            let mut mask = _mm256_movemask_epi8(cmp) as u32;

            if mask == 0 {
                offset += 32;
                continue;
            }

            while mask != 0 && count < max_out {
                let trailing = mask.trailing_zeros() as usize;
                let candidate = offset + trailing;

                if candidate + 18 <= len {
                    let b1 = *slice.get_unchecked(candidate + 1);
                    let b2 = *slice.get_unchecked(candidate + 2);

                    if b1 == 0x8B && b2 == 0x08 {
                        let flg = *slice.get_unchecked(candidate + 3);
                        let xfl = *slice.get_unchecked(candidate + 8);
                        if (flg & 0xE0) == 0 && matches!(xfl, 0 | 2 | 4) {
                            if candidate == 0 {
                                *out_offsets.add(count) = 0;
                                count += 1;
                            } else {
                                let isize = u32::from_le_bytes([
                                    *slice.get_unchecked(candidate - 4),
                                    *slice.get_unchecked(candidate - 3),
                                    *slice.get_unchecked(candidate - 2),
                                    *slice.get_unchecked(candidate - 1),
                                ]);

                                if isize > 0 {
                                    *out_offsets.add(count) = candidate as i64;
                                    count += 1;
                                }
                            }
                        }
                    }
                }

                mask &= mask - 1;
            }

            offset += 32;
        }
    }

    // Scalar tail fallback
    while offset + 18 <= len && count < max_out {
        let b0 = *slice.get_unchecked(offset);
        let b1 = *slice.get_unchecked(offset + 1);
        let b2 = *slice.get_unchecked(offset + 2);

        if b0 == 0x1F && b1 == 0x8B && b2 == 0x08 {
            let flg = *slice.get_unchecked(offset + 3);
            let xfl = *slice.get_unchecked(offset + 8);
            if (flg & 0xE0) == 0 && matches!(xfl, 0 | 2 | 4) {
                if offset == 0 {
                    *out_offsets.add(count) = 0;
                    count += 1;
                    offset += 10;
                    continue;
                }

                let isize = u32::from_le_bytes([
                    *slice.get_unchecked(offset - 4),
                    *slice.get_unchecked(offset - 3),
                    *slice.get_unchecked(offset - 2),
                    *slice.get_unchecked(offset - 1),
                ]);

                if isize > 0 {
                    *out_offsets.add(count) = offset as i64;
                    count += 1;
                    offset += 10;
                    continue;
                }
            }
        }

        offset += 1;
    }

    count as i32
}

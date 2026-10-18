/// Scans a multi-member GZIP stream for individual member start offsets.
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

    let slice = unsafe { core::slice::from_raw_parts(data, len) };
    let out_slice = unsafe { core::slice::from_raw_parts_mut(out_offsets, max_out) };

    #[cfg(target_arch = "x86_64")]
    let (mut count, mut offset) = if is_x86_feature_detected!("avx2") {
        unsafe { scan_gzip_members_avx2(slice, out_slice) }
    } else {
        (0, 0)
    };

    #[cfg(not(target_arch = "x86_64"))]
    let (mut count, mut offset) = (0usize, 0usize);

    scan_gzip_members_scalar(slice, out_slice, &mut count, &mut offset);

    count as i32
}

#[inline(always)]
fn is_valid_gzip_header_at(slice: &[u8], candidate: usize) -> bool {
    let Some(header) = slice.get(candidate..candidate + 18) else {
        return false;
    };
    if header[0] != 0x1F || header[1] != 0x8B || header[2] != 0x08 {
        return false;
    }
    let flg = header[3];
    let xfl = header[8];
    if (flg & 0xE0) != 0 || !matches!(xfl, 0 | 2 | 4) {
        return false;
    }
    if candidate == 0 {
        return true;
    }
    let Some(trailer_bytes) = slice
        .get(candidate.wrapping_sub(4)..candidate)
        .and_then(|s| s.first_chunk::<4>())
    else {
        return false;
    };
    u32::from_le_bytes(*trailer_bytes) > 0
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
fn scan_gzip_members_avx2(slice: &[u8], out_offsets: &mut [i64]) -> (usize, usize) {
    use crate::simd::load_u8x32;
    use core::arch::x86_64::*;

    let len = slice.len();
    let max_out = out_offsets.len();
    let mut count = 0;
    let mut offset = 0;
    let target = _mm256_set1_epi8(0x1F_i8);

    while offset + 32 + 18 <= len && count < max_out {
        let chunk_bytes = slice[offset..offset + 32].first_chunk::<32>().unwrap();
        let chunk = load_u8x32(chunk_bytes);
        let cmp = _mm256_cmpeq_epi8(chunk, target);
        let mut mask = _mm256_movemask_epi8(cmp) as u32;

        while mask != 0 && count < max_out {
            let trailing = mask.trailing_zeros() as usize;
            let candidate = offset + trailing;

            if is_valid_gzip_header_at(slice, candidate) {
                out_offsets[count] = candidate as i64;
                count += 1;
            }

            mask &= mask - 1;
        }

        offset += 32;
    }

    (count, offset)
}

#[inline(always)]
fn scan_gzip_members_scalar(
    slice: &[u8],
    out_offsets: &mut [i64],
    count: &mut usize,
    offset: &mut usize,
) {
    let len = slice.len();
    let max_out = out_offsets.len();

    while *offset + 18 <= len && *count < max_out {
        if is_valid_gzip_header_at(slice, *offset) {
            out_offsets[*count] = *offset as i64;
            *count += 1;
            *offset += 10;
            continue;
        }

        *offset += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_scan_gzip_members_simd_and_scalar_parity() {
        let mut stream = vec![0u8; 128];
        // Member 1 at offset 0
        stream[0] = 0x1F;
        stream[1] = 0x8B;
        stream[2] = 0x08;
        stream[3] = 0x00;
        stream[8] = 0x02;
        // Member 1 trailer isize at 46..50
        stream[46..50].copy_from_slice(&1024u32.to_le_bytes());

        // Member 2 at offset 50 (inside AVX2 region)
        stream[50] = 0x1F;
        stream[51] = 0x8B;
        stream[52] = 0x08;
        stream[53] = 0x00;
        stream[58] = 0x04;
        // Member 2 trailer isize at 96..100
        stream[96..100].copy_from_slice(&512u32.to_le_bytes());

        // Member 3 at offset 100 (inside scalar tail region)
        stream[100] = 0x1F;
        stream[101] = 0x8B;
        stream[102] = 0x08;
        stream[103] = 0x00;
        stream[108] = 0x00;

        let mut offsets_full = [0i64; 8];
        let found_full = unsafe {
            scan_gzip_members(
                stream.as_ptr(),
                stream.len() as u64,
                offsets_full.as_mut_ptr(),
                offsets_full.len() as u32,
            )
        };
        assert_eq!(found_full, 3);
        assert_eq!(&offsets_full[..3], &[0, 50, 100]);

        let mut offsets_scalar = [0i64; 8];
        let mut scalar_count = 0usize;
        let mut scalar_offset = 0usize;
        scan_gzip_members_scalar(
            &stream,
            &mut offsets_scalar,
            &mut scalar_count,
            &mut scalar_offset,
        );
        assert_eq!(scalar_count, 3);
        assert_eq!(&offsets_scalar[..3], &[0, 50, 100]);
    }
}

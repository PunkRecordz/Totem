pub const SECTOR_BYTES: usize = 4096;
pub const HEADER_BYTES: usize = 8192;
pub const CHUNKS_PER_REGION: usize = 1024;
pub const CHUNK_HEADER_BYTES: usize = 5;
pub const MAX_CHUNK_SECTORS: usize = 255;

pub const COMPRESSION_GZIP: u8 = 1;
pub const COMPRESSION_ZLIB: u8 = 2;
pub const COMPRESSION_NONE: u8 = 3;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ChunkLocation {
    Absent,
    UnknownCompression(u8),
    Present {
        compression: u8,
        payload_start: usize,
        payload_length: usize,
    },
}

#[inline(always)]
fn read_be_u32(bytes: &[u8], offset: usize) -> Option<u32> {
    let window = bytes.get(offset..offset.checked_add(4)?)?;
    window.first_chunk::<4>().map(|array| u32::from_be_bytes(*array))
}

// accepts unpadded trailing sectors whenever the declared payload bytes are fully present
pub fn locate_chunk(region: &[u8], chunk_index: usize) -> ChunkLocation {
    if chunk_index >= CHUNKS_PER_REGION || region.len() < HEADER_BYTES {
        return ChunkLocation::Absent;
    }

    let Some(offset_entry) = read_be_u32(region, chunk_index * 4) else {
        return ChunkLocation::Absent;
    };
    if offset_entry == 0 {
        return ChunkLocation::Absent;
    }

    let sector_index = (offset_entry >> 8) as usize;
    let sector_count = (offset_entry & 0xFF) as usize;
    let Some(sector_offset) = sector_index.checked_mul(SECTOR_BYTES) else {
        return ChunkLocation::Absent;
    };
    if sector_offset < HEADER_BYTES || sector_count == 0 {
        return ChunkLocation::Absent;
    }
    let Some(payload_start) = sector_offset.checked_add(CHUNK_HEADER_BYTES) else {
        return ChunkLocation::Absent;
    };
    if payload_start > region.len() {
        return ChunkLocation::Absent;
    }

    let Some(declared_length) = read_be_u32(region, sector_offset) else {
        return ChunkLocation::Absent;
    };
    let declared_length = declared_length as i32 as i64;
    let compressed_size = declared_length - 1;
    let max_sector_capacity = (sector_count as i64) * (SECTOR_BYTES as i64);
    if compressed_size <= 0 || 4 + declared_length > max_sector_capacity {
        return ChunkLocation::Absent;
    }

    let payload_length = compressed_size as usize;
    let Some(payload_end) = payload_start.checked_add(payload_length) else {
        return ChunkLocation::Absent;
    };
    if payload_end > region.len() {
        return ChunkLocation::Absent;
    }

    match region[sector_offset + 4] {
        compression @ (COMPRESSION_GZIP | COMPRESSION_ZLIB | COMPRESSION_NONE) => ChunkLocation::Present {
            compression,
            payload_start,
            payload_length,
        },
        unknown => ChunkLocation::UnknownCompression(unknown),
    }
}

#[inline(always)]
pub const fn round_up(value: usize, alignment: usize) -> usize {
    value.div_ceil(alignment) * alignment
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    pub fn build_region(chunks: &[(usize, u8, &[u8], bool)]) -> Vec<u8> {
        let mut region = vec![0u8; HEADER_BYTES];
        for &(chunk_index, compression, payload, pad) in chunks {
            region.resize(round_up(region.len(), SECTOR_BYTES), 0);
            let sector_index = region.len() / SECTOR_BYTES;
            let frame_length = CHUNK_HEADER_BYTES + payload.len();
            let sector_count = frame_length.div_ceil(SECTOR_BYTES);

            region.extend_from_slice(&((payload.len() + 1) as u32).to_be_bytes());
            region.push(compression);
            region.extend_from_slice(payload);
            if pad {
                region.resize(round_up(region.len(), SECTOR_BYTES), 0);
            }

            let entry = ((sector_index as u32) << 8) | sector_count as u32;
            region[chunk_index * 4..chunk_index * 4 + 4].copy_from_slice(&entry.to_be_bytes());
        }
        region
    }

    #[test]
    fn test_locate_present_absent_unpadded_and_unknown() {
        let region = build_region(&[
            (0, COMPRESSION_NONE, b"abc", true),
            (5, 9, b"zz", true),
            (1023, COMPRESSION_NONE, b"tail", false),
        ]);

        assert!(matches!(
            locate_chunk(&region, 0),
            ChunkLocation::Present { compression: 3, payload_length: 3, .. }
        ));
        assert_eq!(locate_chunk(&region, 1), ChunkLocation::Absent);
        assert_eq!(locate_chunk(&region, 5), ChunkLocation::UnknownCompression(9));
        assert!(matches!(
            locate_chunk(&region, 1023),
            ChunkLocation::Present { payload_length: 4, .. }
        ));
        assert_eq!(region.len() % SECTOR_BYTES, CHUNK_HEADER_BYTES + 4);

        let truncated = &region[..region.len() - 1];
        assert_eq!(locate_chunk(truncated, 1023), ChunkLocation::Absent);
    }
}

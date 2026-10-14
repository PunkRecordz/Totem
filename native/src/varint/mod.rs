pub mod decode;
mod decode_swar;
pub mod encode;
mod encode_swar;
pub mod partition;
pub mod size;

pub use decode::decode_varint_shorts;
pub use encode::encode_shorts_varint;
pub use partition::{count_varint_terminals, find_varint_boundaries, partition_varint_stream};
pub use size::varint_encoded_size;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_count_varint_terminals() {
        let sample = vec![
            0x01,             // 1 terminal (1 byte)
            0x80, 0x01,       // 1 terminal (2 bytes)
            0x80, 0x80, 0x01, // 1 terminal (3 bytes)
            0x05,             // 1 terminal (1 byte)
            0x00,             // 1 terminal (1 byte)
        ];
        let count = unsafe { count_varint_terminals(sample.as_ptr(), sample.len() as u64) };
        assert_eq!(count, 5);
    }

    #[test]
    fn test_partition_varint_stream() {
        let sample = vec![
            0x01,             // short 0
            0x80, 0x01,       // short 1
            0x80, 0x80, 0x01, // short 2
            0x05,             // short 3
            0x00,             // short 4
            0x10,             // short 5
        ];
        let mut byte_starts = [0u64; 3];
        let mut short_offsets = [0u32; 2];
        let mut short_counts = [0u32; 2];

        let total = unsafe {
            partition_varint_stream(
                sample.as_ptr(),
                sample.len() as u64,
                2,
                byte_starts.as_mut_ptr(),
                short_offsets.as_mut_ptr(),
                short_counts.as_mut_ptr(),
            )
        };

        assert_eq!(total, 6);
        assert_eq!(short_offsets[0], 0);
        assert_eq!(short_offsets[1] + short_counts[1], 6);
        assert_eq!(byte_starts[0], 0);
        assert_eq!(byte_starts[2], sample.len() as u64);
    }

    #[test]
    fn test_varint_encoded_size_correctness() {
        let mut sample = Vec::new();
        sample.extend(vec![0u16, 1, 50, 127]);
        sample.extend(vec![128u16, 255, 1000, 16383]);
        sample.extend(vec![16384u16, 32767, 50000, 65535]);

        let calculated_size = unsafe { varint_encoded_size(sample.as_ptr(), sample.len() as u64) };
        let mut encoded = vec![0u8; calculated_size as usize];
        let actual_size = unsafe {
            encode_shorts_varint(
                sample.as_ptr(),
                sample.len() as u64,
                encoded.as_mut_ptr(),
                encoded.len() as u64,
            )
        };

        assert_eq!(calculated_size, actual_size);
        assert_eq!(actual_size, 4 * 1 + 4 * 2 + 4 * 3);
    }

    #[test]
    fn test_roundtrip_all_sizes() {
        let mut sample = Vec::new();
        for i in 0..1000u16 {
            sample.push(i % 128);
            sample.push(128 + (i % 5000));
            sample.push(20000 + (i % 40000));
        }

        let size = unsafe { varint_encoded_size(sample.as_ptr(), sample.len() as u64) };
        let mut encoded = vec![0u8; size as usize];
        let written = unsafe {
            encode_shorts_varint(
                sample.as_ptr(),
                sample.len() as u64,
                encoded.as_mut_ptr(),
                encoded.len() as u64,
            )
        };
        assert_eq!(size, written);

        let mut decoded = vec![0u16; sample.len()];
        let read = unsafe { decode_varint_shorts(encoded.as_ptr(), written, decoded.as_mut_ptr(), sample.len() as u32) };
        assert_eq!(read, written as i64);
        assert_eq!(sample, decoded);
    }

    #[test]
    fn test_encode_shorts_varint_two_bytes_heavy() {
        let mut sample = Vec::new();
        for i in 0..50000u16 {
            sample.push(128 + (i % 16000));
        }

        let calculated_size = unsafe { varint_encoded_size(sample.as_ptr(), sample.len() as u64) };
        assert_eq!(calculated_size, 50000 * 2);

        let mut encoded = vec![0u8; calculated_size as usize];
        let written = unsafe {
            encode_shorts_varint(
                sample.as_ptr(),
                sample.len() as u64,
                encoded.as_mut_ptr(),
                encoded.len() as u64,
            )
        };
        assert_eq!(written, calculated_size);

        let mut decoded = vec![0u16; sample.len()];
        let read = unsafe { decode_varint_shorts(encoded.as_ptr(), written, decoded.as_mut_ptr(), sample.len() as u32) };
        assert_eq!(read, written as i64);
        assert_eq!(sample, decoded);
    }

    #[test]
    fn test_find_varint_boundaries() {
        let sample = vec![0x81, 0x01, 0x02, 0x03, 0x84, 0x01, 0x05];
        let mut boundaries = [0u64; 3];
        unsafe { find_varint_boundaries(sample.as_ptr(), sample.len() as u64, 2, boundaries.as_mut_ptr()) };
        assert_eq!(boundaries[0], 0);
        assert_eq!(boundaries[2], sample.len() as u64);
        assert!(boundaries[1] >= 1 && boundaries[1] < sample.len() as u64);
        assert_eq!(sample[boundaries[1] as usize - 1] & 0x80, 0);
    }
}

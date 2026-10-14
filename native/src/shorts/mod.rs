pub mod lut;
pub mod ops;
pub mod scan;

pub use lut::translate_shorts_lut;
pub use ops::{fill_shorts, replace_shorts};
pub use scan::{count_matching_shorts, scan_matching_shorts};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_replace_shorts() {
        let mut sample = vec![0i16, 1, 0, 2, 3, 0, 4, 5, 0, 0, 6, 7, 8, 9, 10, 0, 11, 0];
        unsafe {
            replace_shorts(sample.as_mut_ptr(), sample.len() as u64, 0, 42);
        }
        assert_eq!(sample, vec![42i16, 1, 42, 2, 3, 42, 4, 5, 42, 42, 6, 7, 8, 9, 10, 42, 11, 42]);
    }

    #[test]
    fn test_fill_shorts() {
        let mut sample = vec![0i16; 35];
        unsafe {
            fill_shorts(sample.as_mut_ptr(), sample.len() as u64, 99);
        }
        assert_eq!(sample, vec![99i16; 35]);
    }

    #[test]
    fn test_translate_shorts_lut() {
        let mut sample = vec![0i16, 1, 2, 3, 4, 5, 0, 1];
        let mut lut = vec![0i16; 65536];
        for i in 0..65536 {
            lut[i] = i as i16;
        }
        lut[0] = 100;
        lut[1] = 101;
        unsafe {
            translate_shorts_lut(sample.as_mut_ptr(), sample.len() as u64, lut.as_ptr());
        }
        assert_eq!(sample, vec![100i16, 101, 2, 3, 4, 5, 100, 101]);

        let mut long_sample = vec![5i16; 128];
        long_sample.extend(vec![7i16; 64]);
        long_sample.extend(vec![2i16; 32]);
        let mut expected = long_sample.clone();

        lut[5] = 500;
        lut[7] = 700;
        for x in expected[0..128].iter_mut() {
            *x = 500;
        }
        for x in expected[128..192].iter_mut() {
            *x = 700;
        }

        unsafe {
            translate_shorts_lut(long_sample.as_mut_ptr(), long_sample.len() as u64, lut.as_ptr());
        }
        assert_eq!(long_sample, expected);
    }

    #[test]
    fn test_count_and_scan_matching_shorts() {
        let mut sample = vec![0u16; 100];
        sample[5] = 42;
        sample[15] = 99;
        sample[40] = 42;
        sample[85] = 123;

        let mut lut = vec![1u8; 65536];
        lut[42] = 0;
        lut[99] = 0;
        lut[123] = 0;

        let count = unsafe { count_matching_shorts(sample.as_ptr(), sample.len() as u64, lut.as_ptr()) };
        assert_eq!(count, 4);

        let mut indices = vec![0i32; 4];
        let written = unsafe {
            scan_matching_shorts(
                sample.as_ptr(),
                sample.len() as u64,
                1000,
                lut.as_ptr(),
                indices.as_mut_ptr(),
                indices.len() as u64,
            )
        };
        assert_eq!(written, 4);
        assert_eq!(indices, vec![1005, 1015, 1040, 1085]);
    }
}

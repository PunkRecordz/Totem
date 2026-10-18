#[cfg(target_arch = "x86_64")]
use core::arch::x86_64::{
    __m128i, __m256i, _mm_storeu_si128, _mm256_loadu_si256, _mm256_storeu_si256,
};

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
pub fn load_u8x32(chunk: &[u8; 32]) -> __m256i {
    unsafe { _mm256_loadu_si256(chunk.as_ptr() as *const __m256i) }
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
pub fn store_u8x32(chunk: &mut [u8; 32], value: __m256i) {
    unsafe { _mm256_storeu_si256(chunk.as_mut_ptr() as *mut __m256i, value) }
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
pub fn store_u8x16(chunk: &mut [u8; 16], value: __m128i) {
    unsafe { _mm_storeu_si128(chunk.as_mut_ptr() as *mut __m128i, value) }
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
pub fn load_u16x16(chunk: &[u16; 16]) -> __m256i {
    unsafe { _mm256_loadu_si256(chunk.as_ptr() as *const __m256i) }
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
pub fn store_u16x16(chunk: &mut [u16; 16], value: __m256i) {
    unsafe { _mm256_storeu_si256(chunk.as_mut_ptr() as *mut __m256i, value) }
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
pub fn store_u16x8(chunk: &mut [u16; 8], value: __m128i) {
    unsafe { _mm_storeu_si128(chunk.as_mut_ptr() as *mut __m128i, value) }
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
pub fn load_i16x16(chunk: &[i16; 16]) -> __m256i {
    unsafe { _mm256_loadu_si256(chunk.as_ptr() as *const __m256i) }
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
pub fn store_i16x16(chunk: &mut [i16; 16], value: __m256i) {
    unsafe { _mm256_storeu_si256(chunk.as_mut_ptr() as *mut __m256i, value) }
}

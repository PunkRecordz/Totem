use core::ffi::c_void;
use core::ptr;
use std::cell::RefCell;

use libdeflate_sys::{
    libdeflate_alloc_compressor, libdeflate_alloc_decompressor, libdeflate_compressor,
    libdeflate_decompressor, libdeflate_deflate_compress, libdeflate_deflate_compress_bound,
    libdeflate_deflate_decompress_ex, libdeflate_free_compressor, libdeflate_free_decompressor,
    libdeflate_gzip_compress, libdeflate_gzip_compress_bound, libdeflate_gzip_decompress_ex,
    libdeflate_result, libdeflate_result_LIBDEFLATE_INSUFFICIENT_SPACE,
    libdeflate_result_LIBDEFLATE_SUCCESS, libdeflate_zlib_compress, libdeflate_zlib_compress_bound,
    libdeflate_zlib_decompress_ex,
};

pub const MIN_LEVEL: i32 = 0;
pub const MAX_LEVEL: i32 = 12;
const LEVEL_SLOTS: usize = (MAX_LEVEL + 1) as usize;
const SUCCESS: libdeflate_result = libdeflate_result_LIBDEFLATE_SUCCESS;
const INSUFFICIENT_SPACE: libdeflate_result = libdeflate_result_LIBDEFLATE_INSUFFICIENT_SPACE;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DeflateFormat {
    Raw,
    Zlib,
    Gzip,
}

impl DeflateFormat {
    pub fn from_code(code: u32) -> Option<Self> {
        match code {
            0 => Some(Self::Raw),
            1 => Some(Self::Zlib),
            2 => Some(Self::Gzip),
            _ => None,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InflateError {
    BadData,
    InsufficientSpace,
}

struct CodecCache {
    decompressor: *mut libdeflate_decompressor,
    compressors: [*mut libdeflate_compressor; LEVEL_SLOTS],
}

impl CodecCache {
    const fn new() -> Self {
        Self {
            decompressor: ptr::null_mut(),
            compressors: [ptr::null_mut(); LEVEL_SLOTS],
        }
    }

    fn decompressor(&mut self) -> *mut libdeflate_decompressor {
        if self.decompressor.is_null() {
            self.decompressor = unsafe { libdeflate_alloc_decompressor() };
            assert!(!self.decompressor.is_null(), "libdeflate decompressor allocation failed");
        }
        self.decompressor
    }

    fn compressor(&mut self, level: i32) -> *mut libdeflate_compressor {
        let slot = &mut self.compressors[level as usize];
        if slot.is_null() {
            *slot = unsafe { libdeflate_alloc_compressor(level) };
            assert!(!slot.is_null(), "libdeflate compressor allocation failed");
        }
        *slot
    }
}

impl Drop for CodecCache {
    fn drop(&mut self) {
        if !self.decompressor.is_null() {
            unsafe { libdeflate_free_decompressor(self.decompressor) };
        }
        for compressor in self.compressors {
            if !compressor.is_null() {
                unsafe { libdeflate_free_compressor(compressor) };
            }
        }
    }
}

thread_local! {
    // keyed by os thread: virtual threads share their carrier's codecs safely while pinned in a downcall
    static CODEC_CACHE: RefCell<CodecCache> = const { RefCell::new(CodecCache::new()) };
}

pub fn is_valid_level(level: i32) -> bool {
    (MIN_LEVEL..=MAX_LEVEL).contains(&level)
}

pub fn inflate_into_slice_ex(
    format: DeflateFormat,
    source: &[u8],
    destination: &mut [u8],
) -> Result<(usize, usize), InflateError> {
    unsafe { inflate_into_ex(format, source, destination.as_mut_ptr(), destination.len()) }
}

pub unsafe fn inflate_into(
    format: DeflateFormat,
    source: &[u8],
    destination: *mut u8,
    capacity: usize,
) -> Result<usize, InflateError> {
    unsafe { inflate_into_ex(format, source, destination, capacity) }.map(|(_, produced)| produced)
}

pub unsafe fn inflate_into_ex(
    format: DeflateFormat,
    source: &[u8],
    destination: *mut u8,
    capacity: usize,
) -> Result<(usize, usize), InflateError> {
    CODEC_CACHE.with_borrow_mut(|cache| {
        let decompressor = cache.decompressor();
        let mut consumed = 0usize;
        let mut produced = 0usize;
        let input = source.as_ptr() as *const c_void;
        let output = destination as *mut c_void;

        let result: libdeflate_result = unsafe {
            match format {
                DeflateFormat::Raw => libdeflate_deflate_decompress_ex(
                    decompressor, input, source.len(), output, capacity, &mut consumed, &mut produced,
                ),
                DeflateFormat::Zlib => libdeflate_zlib_decompress_ex(
                    decompressor, input, source.len(), output, capacity, &mut consumed, &mut produced,
                ),
                DeflateFormat::Gzip => libdeflate_gzip_decompress_ex(
                    decompressor, input, source.len(), output, capacity, &mut consumed, &mut produced,
                ),
            }
        };

        match result {
            SUCCESS => Ok((consumed, produced)),
            INSUFFICIENT_SPACE => Err(InflateError::InsufficientSpace),
            _ => Err(InflateError::BadData),
        }
    })
}

pub fn inflate_into_vec(
    format: DeflateFormat,
    source: &[u8],
    scratch: &mut Vec<u8>,
    max_capacity: usize,
) -> Result<usize, InflateError> {
    loop {
        let capacity = scratch.capacity();
        let attempt = unsafe { inflate_into(format, source, scratch.as_mut_ptr(), capacity) };

        match attempt {
            Ok(produced) => {
                unsafe { scratch.set_len(produced) };
                return Ok(produced);
            }
            Err(InflateError::InsufficientSpace) if capacity < max_capacity => {
                let next_capacity = (capacity.max(64 * 1024) * 2).min(max_capacity);
                scratch.clear();
                scratch.reserve_exact(next_capacity);
            }
            Err(error) => return Err(error),
        }
    }
}

pub fn deflate_bound(format: DeflateFormat, length: usize) -> usize {
    // libdeflate bounds are compressor-independent since 1.16; a null compressor is explicitly valid
    unsafe {
        match format {
            DeflateFormat::Raw => libdeflate_deflate_compress_bound(ptr::null_mut(), length),
            DeflateFormat::Zlib => libdeflate_zlib_compress_bound(ptr::null_mut(), length),
            DeflateFormat::Gzip => libdeflate_gzip_compress_bound(ptr::null_mut(), length),
        }
    }
}

pub fn deflate_into_slice(
    format: DeflateFormat,
    level: i32,
    source: &[u8],
    destination: &mut [u8],
) -> Option<usize> {
    unsafe { deflate_into(format, level, source, destination.as_mut_ptr(), destination.len()) }
}

pub unsafe fn deflate_into(
    format: DeflateFormat,
    level: i32,
    source: &[u8],
    destination: *mut u8,
    capacity: usize,
) -> Option<usize> {
    debug_assert!(is_valid_level(level));

    CODEC_CACHE.with_borrow_mut(|cache| {
        let compressor = cache.compressor(level);
        let input = source.as_ptr() as *const c_void;
        let output = destination as *mut c_void;

        let produced = unsafe {
            match format {
                DeflateFormat::Raw => {
                    libdeflate_deflate_compress(compressor, input, source.len(), output, capacity)
                }
                DeflateFormat::Zlib => {
                    libdeflate_zlib_compress(compressor, input, source.len(), output, capacity)
                }
                DeflateFormat::Gzip => {
                    libdeflate_gzip_compress(compressor, input, source.len(), output, capacity)
                }
            }
        };

        (produced != 0).then_some(produced)
    })
}

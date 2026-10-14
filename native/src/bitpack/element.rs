pub trait UnpackElement: Copy {
    const ZERO: Self;
    fn from_u64(value: u64) -> Self;
}

impl UnpackElement for u16 {
    const ZERO: Self = 0;

    #[inline(always)]
    fn from_u64(value: u64) -> Self {
        value as u16
    }
}

impl UnpackElement for u64 {
    const ZERO: Self = 0;

    #[inline(always)]
    fn from_u64(value: u64) -> Self {
        value
    }
}

pub trait PackElement: Copy {
    fn to_u64(self) -> u64;
}

impl PackElement for u16 {
    #[inline(always)]
    fn to_u64(self) -> u64 {
        self as u64
    }
}

impl PackElement for u64 {
    #[inline(always)]
    fn to_u64(self) -> u64 {
        self
    }
}

#[inline(always)]
pub fn decode_u64<const BIG_ENDIAN: bool>(word: u64) -> u64 {
    if BIG_ENDIAN {
        u64::from_be(word)
    } else {
        word
    }
}

#[inline(always)]
pub fn encode_u64<const BIG_ENDIAN: bool>(word: u64) -> u64 {
    if BIG_ENDIAN {
        word.to_be()
    } else {
        word
    }
}

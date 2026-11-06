#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ByteSliceDescriptor {
    pub address: u64,
    pub length: u64,
}

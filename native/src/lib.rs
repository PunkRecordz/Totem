pub mod anvil;
pub mod bitpack;
pub mod deflate;
pub mod gzip;
pub mod nbt;
pub mod shorts;
mod simd;
pub mod slice_descriptor;
pub mod varint;

pub use anvil::{
    totem_anvil_frame_bound, totem_anvil_frame_chunk, totem_anvil_frame_chunks, totem_anvil_inflate_chunks,
};
pub use bitpack::{
    pack_longs_to_longs, pack_shorts_to_longs, unpack_longs_to_longs, unpack_longs_to_shorts,
};
pub use deflate::{
    totem_deflate, totem_deflate_bound, totem_gzip_compress_slices, totem_gzip_inflate_members,
    totem_gzip_slices_bound, totem_inflate,
};
pub use gzip::scan_gzip_members;
pub use nbt::{calculate_compound_size, calculate_list_size, find_compound_entry};
pub use shorts::{
    count_matching_shorts, fill_shorts, replace_shorts, scan_matching_shorts, totem_fill_shorts_parallel,
    totem_replace_shorts_parallel, totem_translate_shorts_lut_parallel, translate_shorts_lut,
};
pub use slice_descriptor::ByteSliceDescriptor;
pub use varint::{
    count_varint_terminals, decode_varint_shorts, encode_shorts_varint, find_varint_boundaries,
    partition_varint_stream, totem_decode_varint_shorts_parallel, totem_encode_shorts_varint_parallel,
    totem_varint_partition_sizes, varint_encoded_size,
};

#[unsafe(no_mangle)]
pub extern "C" fn totem_native_parallelism() -> i32 {
    rayon::current_num_threads() as i32
}

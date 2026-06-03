package org.punkrecordz.totem.io.compression

object CompressionType {

    val NONE: Compression = NoneCompression

    val GZIP: Compression = GzipCompression()

    val ZLIB: Compression = ZlibCompression()

}

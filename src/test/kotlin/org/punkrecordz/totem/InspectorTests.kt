package org.punkrecordz.totem

import org.junit.jupiter.api.Test
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.io.compression.CompressionType
import org.punkrecordz.totem.io.stream.NativeByteArrayOutputStream
import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.Tags
import java.lang.foreign.Arena
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals

class InspectorTests {

    @Test
    fun testInspectMetadataHeader() {
        Arena.ofConfined().use { arena ->
            val compound = Tags.compound {
                putInt("Version", 3)
                putInt("DataVersion", 4440)
                putString("Name", "InspectTest")
                put("Metadata", Tags.compound {
                    putString("Author", "Levely")
                })
            }

            val uncompressedSegment = Totem.save("Schematic", compound, arena, CompressionType.NONE)
            val (uncompressedName, uncompressedRoot) = Totem.inspect(uncompressedSegment, arena)
            assertEquals("Schematic", uncompressedName)
            assertEquals(3, uncompressedRoot.getInt("Version"))
            assertEquals(4440, uncompressedRoot.getInt("DataVersion"))
            assertEquals("InspectTest", uncompressedRoot.getString("Name"))
            assertEquals("Levely", uncompressedRoot.getCompound("Metadata")?.getString("Author"))

            val gzipSegment = Totem.save("Schematic", compound, arena, CompressionType.GZIP)
            val (gzipName, gzipRoot) = Totem.inspect(gzipSegment, arena)
            assertEquals("Schematic", gzipName)
            assertEquals(3, gzipRoot.getInt("Version"))
            assertEquals(4440, gzipRoot.getInt("DataVersion"))
            assertEquals("InspectTest", gzipRoot.getString("Name"))
            assertEquals("Levely", gzipRoot.getCompound("Metadata")?.getString("Author"))
        }
    }

    @Test
    fun testHeaderFirstSerializationAndInspect() {
        Arena.ofConfined().use { arena ->
            val hugePayload = Tags.nativeByteArray(2 * 1024 * 1024, arena)
            hugePayload[0] = 7

            val compound = Tags.compound {
                put("BlockData", hugePayload)
                putInt("Version", 2)
                putInt("DataVersion", 4440)
                putShort("Width", 1000)
                putShort("Height", 1000)
                putShort("Length", 1000)
            }

            val tempGzipFile = createTempFile("totem_inspect_header_first_", ".schem")
            val tempRawFile = createTempFile("totem_inspect_header_first_raw_", ".nbt")

            try {
                Totem.save("Schematic", compound, tempGzipFile, CompressionType.GZIP)
                Totem.save("Schematic", compound, tempRawFile, CompressionType.NONE)

                val (gzipName, gzipRoot) = Totem.inspect(tempGzipFile, arena, maxUncompressedBytes = 4096)
                assertEquals("Schematic", gzipName)
                assertEquals(2, gzipRoot.getInt("Version"))
                assertEquals(4440, gzipRoot.getInt("DataVersion"))
                assertEquals(1000.toShort(), gzipRoot.getShort("Width"))
                assertEquals(1000.toShort(), gzipRoot.getShort("Height"))
                assertEquals(1000.toShort(), gzipRoot.getShort("Length"))

                val (rawName, rawRoot) = Totem.inspect(tempRawFile, arena, maxUncompressedBytes = 4096)
                assertEquals("Schematic", rawName)
                assertEquals(2, rawRoot.getInt("Version"))
                assertEquals(4440, rawRoot.getInt("DataVersion"))
                assertEquals(1000.toShort(), rawRoot.getShort("Width"))
            } finally {
                tempGzipFile.deleteIfExists()
                tempRawFile.deleteIfExists()
            }
        }
    }

    @Test
    fun testInspectElidesThirdPartyHugeArrayBeforeMetadata() {
        Arena.ofConfined().use { arena ->
            val rawStream = NativeByteArrayOutputStream(arena)
            rawStream.writeByte(TagType.COMPOUND.id.toByte())
            rawStream.writeString("Schematic")

            val arraySize = 4 * 1024 * 1024
            rawStream.writeByte(TagType.BYTE_ARRAY.id.toByte())
            rawStream.writeString("BlockData")
            rawStream.writeInt(arraySize)

            val dummyBlockSegment = arena.allocateUninitialized(arraySize.toLong())
            dummyBlockSegment.fill(42)
            rawStream.writeSegment(dummyBlockSegment)

            rawStream.writeByte(TagType.INT.id.toByte())
            rawStream.writeString("DataVersion")
            rawStream.writeInt(4440)

            rawStream.writeByte(TagType.SHORT.id.toByte())
            rawStream.writeString("Width")
            rawStream.writeShort(1000)

            rawStream.writeByte(TagType.END.id.toByte())

            val rawNbtSegment = rawStream.toContiguousSegment(arena)

            val (rawName, rawRoot) = Totem.inspect(
                segment = rawNbtSegment,
                arena = arena,
                maxUncompressedBytes = 65536L,
            )
            assertEquals("Schematic", rawName)
            assertEquals(0, rawRoot.getByteArray("BlockData")?.size)
            assertEquals(4440, rawRoot.getInt("DataVersion"))
            assertEquals(1000.toShort(), rawRoot.getShort("Width"))

            val compressedSegment = CompressionType.GZIP.compress(rawNbtSegment, arena)
            val (gzipName, gzipRoot) = Totem.inspect(
                segment = compressedSegment,
                arena = arena,
                maxUncompressedBytes = 65536L,
            )
            assertEquals("Schematic", gzipName)
            assertEquals(0, gzipRoot.getByteArray("BlockData")?.size)
            assertEquals(4440, gzipRoot.getInt("DataVersion"))
            assertEquals(1000.toShort(), gzipRoot.getShort("Width"))
        }
    }

}

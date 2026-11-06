package org.punkrecordz.totem

import org.junit.jupiter.api.Test
import org.punkrecordz.totem.ffi.TotemCodecSys
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.io.compression.CompressionType
import org.punkrecordz.totem.io.compression.GzipCompression
import org.punkrecordz.totem.io.compression.ZlibCompression
import org.punkrecordz.totem.tag.IntArrayTag
import org.punkrecordz.totem.tag.Tags
import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedArenaAndLargeGzipTests {

    @Test
    fun testSingleAndMultiMemberGzipAbove512MiBUseNativeFastPathOnSharedArena() {
        val largePayloadSize = 540L * 1024L * 1024L
        val gzip = GzipCompression(level = 1)

        Arena.ofShared().use { sharedSourceArena ->
            val rawLarge = sharedSourceArena.allocate(largePayloadSize)
            rawLarge.set(ValueLayout.JAVA_LONG_UNALIGNED, 0L, 0x0123_4567_89AB_CDEFL)
            rawLarge.set(ValueLayout.JAVA_LONG_UNALIGNED, largePayloadSize - 8L, 0x7FED_CBA9_8765_4321L)

            // 1. monolithic single-member gzip (> 512 mib uncompressed)
            val singleBound = TotemCodecSys.deflateBound(TotemCodecSys.FORMAT_GZIP, largePayloadSize)
            val singleCompressedBuffer = sharedSourceArena.allocateUninitialized(singleBound)
            val singleCompressedBytes = TotemCodecSys.deflate(
                format = TotemCodecSys.FORMAT_GZIP,
                level = 1,
                source = rawLarge,
                destination = singleCompressedBuffer,
            )
            assertTrue(singleCompressedBytes > 0L)
            val singleCompressedSlice = singleCompressedBuffer.asSlice(0L, singleCompressedBytes)

            Arena.ofShared().use { callerSharedArena ->
                val restoredSingle = gzip.decompress(singleCompressedSlice, callerSharedArena)
                assertEquals(largePayloadSize, restoredSingle.byteSize())
                assertEquals(0x0123_4567_89AB_CDEFL, restoredSingle.get(ValueLayout.JAVA_LONG_UNALIGNED, 0L))
                assertEquals(
                    0x7FED_CBA9_8765_4321L,
                    restoredSingle.get(ValueLayout.JAVA_LONG_UNALIGNED, largePayloadSize - 8L),
                )
            }

            // 2. parallel multi-member gzip (> 512 mib total uncompressed)
            val multiCompressed = gzip.compress(rawLarge, sharedSourceArena)
            Arena.ofShared().use { callerSharedArena ->
                val restoredMulti = gzip.decompress(multiCompressed, callerSharedArena)
                assertEquals(largePayloadSize, restoredMulti.byteSize())
                assertEquals(0x0123_4567_89AB_CDEFL, restoredMulti.get(ValueLayout.JAVA_LONG_UNALIGNED, 0L))
                assertEquals(
                    0x7FED_CBA9_8765_4321L,
                    restoredMulti.get(ValueLayout.JAVA_LONG_UNALIGNED, largePayloadSize - 8L),
                )
            }
        }
    }

    @Test
    fun testMultiMemberPerMemberExpansionGuardRejectsSpoofedMemberIsize() {
        val gzip = GzipCompression(level = 1)
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { first -> first.write(ByteArray(128) { 1 }) }
        GZIPOutputStream(output).use { second -> second.write(ByteArray(128) { 2 }) }
        val twoMembers = output.toByteArray()

        // spoof last member's 32-bit isize trailer far beyond memberCompressedBytes * 1032
        MemorySegment.ofArray(twoMembers).set(
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),
            twoMembers.size.toLong() - 4L,
            600 * 1024 * 1024,
        )

        Arena.ofShared().use { sharedArena ->
            val sharedInput = sharedArena.allocateUninitialized(twoMembers.size.toLong())
            MemorySegment.copy(MemorySegment.ofArray(twoMembers), 0L, sharedInput, 0L, twoMembers.size.toLong())

            assertFailsWith<IllegalArgumentException> {
                gzip.decompress(sharedInput, sharedArena)
            }
        }
    }

    @Test
    fun testTotemLoadAndLoadTagUnmapSourceFileImmediatelyWhenCallerUsesSharedArena() {
        McaTestFixture.withTempDirectory("totem_204_unmap_") { directory ->
            val gzipSchemPath = directory.resolve("sample_gzip.schem")
            val rawNbtPath = directory.resolve("sample_raw.nbt")
            val tagPath = directory.resolve("sample_tag.dat")

            val rootCompound = Tags.compound {
                putInt("DataVersion", 4189)
                putString("Author", "PunkRecordz")
                putByteArray("Blocks", ByteArray(65_536) { index -> (index and 0x7F).toByte() })
            }
            Totem.save("Schematic", rootCompound, gzipSchemPath, CompressionType.GZIP)
            Totem.save("Uncompressed", rootCompound, rawNbtPath, CompressionType.NONE)
            Totem.saveTag(Tags.intArray(intArrayOf(10, 20, 30, 40)), tagPath, CompressionType.GZIP)

            Arena.ofShared().use { callerSharedArena ->
                val (gzipName, loadedGzip) = Totem.load(gzipSchemPath, callerSharedArena)
                val (rawName, loadedRaw) = Totem.load(rawNbtPath, callerSharedArena)
                val loadedIntArray = Totem.loadTag<IntArrayTag>(tagPath, callerSharedArena)

                // on windows, Files.delete fails if any mmap handle is still open in callerSharedArena
                Files.delete(gzipSchemPath)
                Files.delete(rawNbtPath)
                Files.delete(tagPath)
                assertFalse(Files.exists(gzipSchemPath))
                assertFalse(Files.exists(rawNbtPath))
                assertFalse(Files.exists(tagPath))

                assertEquals("Schematic", gzipName)
                assertEquals(4189, loadedGzip.getInt("DataVersion"))
                assertEquals(65_536, loadedGzip.getByteArray("Blocks")?.size)

                assertEquals("Uncompressed", rawName)
                assertEquals("PunkRecordz", loadedRaw.getString("Author"))
                assertEquals(4, loadedIntArray.size)
                assertEquals(30, loadedIntArray[2])
            }
        }
    }

    @Test
    fun testStreamingInflateCrc32AndInspectSupportSharedArenaSegmentsOnJdk24() {
        val zlib = ZlibCompression(level = 1)
        val gzip = GzipCompression(level = 1)

        Arena.ofShared().use { sharedArena ->
            // 1. > 16 mib zlib payload forces DeflateStreamInflater with sharedArena input + output
            val rawZlibPayload = ByteArray(18 * 1024 * 1024) { index -> ((index * 29 + 3) and 0x7F).toByte() }
            val sharedZlibCompressed = zlib.compress(MemorySegment.ofArray(rawZlibPayload), sharedArena)
            val restoredZlib = zlib.decompress(sharedZlibCompressed, sharedArena)
            assertEquals(rawZlibPayload.size.toLong(), restoredZlib.byteSize())
            assertEquals(-1L, MemorySegment.ofArray(rawZlibPayload).mismatch(restoredZlib))

            // 2. > 1024 gzip members forces GzipStreamFallback + DeflateStreamInflater + CRC32 on sharedArena
            val manyMembersBytes = ByteArrayOutputStream().also { buffer ->
                repeat(1030) { memberIndex ->
                    GZIPOutputStream(buffer).use { stream ->
                        stream.write(ByteArray(32) { offset -> (memberIndex + offset).toByte() })
                    }
                }
            }.toByteArray()

            val sharedManyMembers = sharedArena.allocateUninitialized(manyMembersBytes.size.toLong())
            MemorySegment.copy(MemorySegment.ofArray(manyMembersBytes), 0L, sharedManyMembers, 0L, manyMembersBytes.size.toLong())
            val restoredMany = gzip.decompress(sharedManyMembers, sharedArena)
            assertEquals(1030L * 32L, restoredMany.byteSize())

            // 3. Totem.inspect on a GZIP segment backed by Arena.ofShared()
            val inspectRoot = Tags.compound {
                putInt("DataVersion", 3953)
                putShort("Width", 512)
                putByteArray("BlockData", ByteArray(256 * 1024) { index -> (index and 0xFF).toByte() })
            }
            val sharedInspectInput = Totem.save("Schematic", inspectRoot, sharedArena, CompressionType.GZIP)
            val (inspectedName, inspectedCompound) = Totem.inspect(sharedInspectInput, sharedArena)
            assertEquals("Schematic", inspectedName)
            assertEquals(3953, inspectedCompound.getInt("DataVersion"))
            assertEquals(512.toShort(), inspectedCompound.getShort("Width"))
        }
    }

}

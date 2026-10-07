package org.punkrecordz.totem

import net.querz.nbt.io.NBTUtil
import org.junit.jupiter.api.Test
import org.punkrecordz.totem.impl.native.NativeByteArrayTag
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.Tags
import org.punkrecordz.totem.view.toVarIntShortArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import net.querz.nbt.tag.CompoundTag as QuerzCompoundTag

class UnitTests {

    @Test
    fun testHeapPrimitiveTags() {
        val byteTag = Tags.byte(10.toByte())
        val shortTag = Tags.short(42.toShort())
        val intTag = Tags.int(100)
        val longTag = Tags.long(1000L)
        val floatTag = Tags.float(3.14f)
        val doubleTag = Tags.double(2.71828)
        val stringTag = Tags.string("Totem")

        assertEquals(10.toByte(), byteTag.value)
        assertEquals(42.toShort(), shortTag.value)
        assertEquals(100, intTag.value)
        assertEquals(1000L, longTag.value)
        assertEquals(3.14f, floatTag.value)
        assertEquals(2.71828, doubleTag.value)
        assertEquals("Totem", stringTag.value)

        assertEquals(TagType.BYTE, byteTag.key)
        assertEquals(TagType.SHORT, shortTag.key)
        assertEquals(TagType.INT, intTag.key)
        assertEquals(TagType.LONG, longTag.key)
        assertEquals(TagType.FLOAT, floatTag.key)
        assertEquals(TagType.DOUBLE, doubleTag.key)
        assertEquals(TagType.STRING, stringTag.key)
    }

    @Test
    fun testHeapArrayTags() {
        val byteArrayTag = Tags.byteArray(byteArrayOf(1, 2, 3))
        val intArrayTag = Tags.intArray(intArrayOf(10, 20, 30))
        val longArrayTag = Tags.longArray(longArrayOf(100L, 200L, 300L))

        assertEquals(3, byteArrayTag.size)
        assertEquals(1, byteArrayTag[0])
        assertEquals(2, byteArrayTag[1])
        assertEquals(3, byteArrayTag[2])

        assertEquals(3, intArrayTag.size)
        assertEquals(10, intArrayTag[0])
        assertEquals(20, intArrayTag[1])
        assertEquals(30, intArrayTag[2])

        assertEquals(3, longArrayTag.size)
        assertEquals(100L, longArrayTag[0])
        assertEquals(200L, longArrayTag[1])
        assertEquals(300L, longArrayTag[2])
    }

    @Test
    fun testNativeArrayTags() {
        Arena.ofConfined().use { arena ->
            val nativeByteArray = Tags.nativeByteArray(5, arena)
            assertEquals(5, nativeByteArray.size)

            val nativeIntArray = Tags.nativeIntArray(3, arena)
            assertEquals(3, nativeIntArray.size)

            val nativeLongArray = Tags.nativeLongArray(2, arena)
            assertEquals(2, nativeLongArray.size)
        }
    }

    @Test
    fun testTagCopyAndPinInvariants() {
        Arena.ofConfined().use { arena ->
            val nativeByteArray = Tags.nativeByteArray(3, arena)

            // Set some values in off-heap memory (skip size header)
            val segment = (nativeByteArray as NativeByteArrayTag).segment
            segment.set(ValueLayout.JAVA_BYTE, 4L, 42.toByte())
            segment.set(ValueLayout.JAVA_BYTE, 5L, 43.toByte())
            segment.set(ValueLayout.JAVA_BYTE, 6L, 44.toByte())

            // Pin returns a JVM heap representation
            val pinned = nativeByteArray.pin()
            assertEquals(3, pinned.size)
            assertEquals(42.toByte(), pinned[0])
            assertEquals(43.toByte(), pinned[1])
            assertEquals(44.toByte(), pinned[2])

            // Copy returns a JVM heap representation decoupled from FFM
            val copied = nativeByteArray.copy()
            assertEquals(3, copied.size)
            assertEquals(42.toByte(), copied[0])
            assertEquals(43.toByte(), copied[1])
            assertEquals(44.toByte(), copied[2])
            assertNotSame(nativeByteArray, copied)
        }
    }

    @Test
    fun testCompoundTagDSL() {
        val compound = Tags.compound {
            putByte("byteKey", 5.toByte())
            putString("stringKey", "Hello")
            put("nested", Tags.compound {
                putInt("intKey", 42)
            })
        }

        assertEquals(5.toByte(), compound.getByte("byteKey"))
        assertEquals("Hello", compound.getString("stringKey"))

        val nested = compound.getCompound("nested")
        assertTrue(nested != null)
        assertEquals(42, nested.getInt("intKey"))
    }

    @Test
    fun testNegativeByteArrayLengthThrowsException() {
        // issue #1 repro: 11 bytes with byte array tag of length -7
        val bytes = byteArrayOf(
            0x0A, 0x00, 0x00,
            0x07, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xF9.toByte(),
            0x00,
        )

        Arena.ofConfined().use { arena ->
            val (_, root) = Totem.load(bytes, arena)

            assertFailsWith<IllegalArgumentException> {
                root.containsKey("x")
            }
        }
    }

    @Test
    fun testNegativeIntArrayLengthThrowsException() {
        // malformed compound with int array tag of length -1
        val bytes = byteArrayOf(
            0x0A, 0x00, 0x00,
            0x0B, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x00,
        )

        Arena.ofConfined().use { arena ->
            val (_, root) = Totem.load(bytes, arena)

            assertFailsWith<IllegalArgumentException> {
                root.containsKey("x")
            }
        }
    }

    @Test
    fun testNegativeLongArrayLengthThrowsException() {
        // malformed compound with long array tag of length -1
        val bytes = byteArrayOf(
            0x0A, 0x00, 0x00,
            0x0C, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x00,
        )

        Arena.ofConfined().use { arena ->
            val (_, root) = Totem.load(bytes, arena)

            assertFailsWith<IllegalArgumentException> {
                root.containsKey("x")
            }
        }
    }

    @Test
    fun testNegativeListTagCountThrowsException() {
        // malformed compound with list tag of count -1
        val bytes = byteArrayOf(
            0x0A, 0x00, 0x00,
            0x09, 0x00, 0x00,
            0x01,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x00,
        )

        Arena.ofConfined().use { arena ->
            val (_, root) = Totem.load(bytes, arena)

            assertFailsWith<IllegalArgumentException> {
                root.containsKey("x")
            }
        }
    }

    @Test
    fun testTruncatedVarIntThrowsException() {
        Arena.ofConfined().use { arena ->
            val nativeByteArray = Tags.nativeByteArray(2, arena)
            val segment = (nativeByteArray as NativeByteArrayTag).segment

            segment.set(ValueLayout.JAVA_BYTE, 4L, 0x01.toByte())
            segment.set(ValueLayout.JAVA_BYTE, 5L, 0x80.toByte())

            assertFailsWith<IllegalArgumentException> {
                nativeByteArray.toVarIntShortArray(2, arena)
            }
        }
    }

    @Test
    fun testIncompleteVarIntsThrowsException() {
        Arena.ofConfined().use { arena ->
            val nativeByteArray = Tags.nativeByteArray(3, arena)
            val segment = (nativeByteArray as NativeByteArrayTag).segment

            segment.set(ValueLayout.JAVA_BYTE, 4L, 1.toByte())
            segment.set(ValueLayout.JAVA_BYTE, 5L, 2.toByte())
            segment.set(ValueLayout.JAVA_BYTE, 6L, 3.toByte())

            assertFailsWith<IllegalArgumentException> {
                nativeByteArray.toVarIntShortArray(400, arena)
            }
        }
    }

    @Test
    fun testModifiedUtf8EncodingAndDecoding() {
        val testStrings = listOf(
            "Hello, World!",
            "",
            "a",
            "Special characters: é, à, ç, ü, ñ, ß",
            "Null byte: \u0000 between chars",
            "Emoji: 😀 and 🚀 and 🎉",
            "Mixed: \u0000 -> é -> 😀 -> end",
        )

        for (testString in testStrings) {
            val encodedBytes = MemoryLayouts.encodeString(testString)
            val decodedString = MemoryLayouts.decodeString(encodedBytes)

            assertEquals(testString, decodedString)
        }
    }

    @Test
    fun testModifiedUtf8NullAndEmojiByteRepresentation() {
        val nullEncoded = MemoryLayouts.encodeString("\u0000")
        assertEquals(2, nullEncoded.size)
        assertEquals(0xC0.toByte(), nullEncoded[0])
        assertEquals(0x80.toByte(), nullEncoded[1])

        val emojiEncoded = MemoryLayouts.encodeString("😀")
        assertEquals(6, emojiEncoded.size)

        val expectedEmojiBytes = byteArrayOf(
            0xED.toByte(), 0xA0.toByte(), 0xBD.toByte(),
            0xED.toByte(), 0xB8.toByte(), 0x80.toByte(),
        )

        for (index in expectedEmojiBytes.indices) {
            assertEquals(expectedEmojiBytes[index], emojiEncoded[index])
        }
    }

    @Test
    fun testModifiedUtf8CrossCompatibilityWithDataStreams() {
        val testStrings = listOf(
            "Hello, World!",
            "Null \u0000 char",
            "Emoji test 😀 rocket 🚀",
            "Accents: éàç",
        )

        for (testString in testStrings) {
            val byteArrayOutputStream = ByteArrayOutputStream()
            val dataOutputStream = DataOutputStream(byteArrayOutputStream)
            dataOutputStream.writeUTF(testString)

            val javaWrittenBytes = byteArrayOutputStream.toByteArray()
            val length = ((javaWrittenBytes[0].toInt() and 0xFF) shl 8) or (javaWrittenBytes[1].toInt() and 0xFF)
            val payloadBytes = javaWrittenBytes.copyOfRange(2, 2 + length)

            val totemDecoded = MemoryLayouts.decodeString(payloadBytes)
            assertEquals(testString, totemDecoded)

            val totemEncoded = MemoryLayouts.encodeString(testString)
            val combinedBytes = ByteArray(2 + totemEncoded.size)
            combinedBytes[0] = ((totemEncoded.size shr 8) and 0xFF).toByte()
            combinedBytes[1] = (totemEncoded.size and 0xFF).toByte()
            System.arraycopy(totemEncoded, 0, combinedBytes, 2, totemEncoded.size)

            val dataInputStream = DataInputStream(ByteArrayInputStream(combinedBytes))
            val javaDecoded = dataInputStream.readUTF()
            assertEquals(testString, javaDecoded)
        }
    }

    @Test
    fun testModifiedUtf8LengthCheckThrowsOnOverflow() {
        val largeString = "A".repeat(70_000)

        assertFailsWith<IllegalArgumentException> {
            MemoryLayouts.stringByteLength(largeString)
        }

        assertFailsWith<IllegalArgumentException> {
            MemoryLayouts.encodeString(largeString)
        }

        val multiByteLargeString = "\u4e2d".repeat(30_000)

        assertFailsWith<IllegalArgumentException> {
            MemoryLayouts.stringByteLength(multiByteLargeString)
        }

        assertFailsWith<IllegalArgumentException> {
            MemoryLayouts.encodeString(multiByteLargeString)
        }

        Arena.ofConfined().use { arena ->
            val compound = Tags.compound()
            compound.putString("large", largeString)

            assertFailsWith<IllegalArgumentException> {
                Totem.save("root", compound, arena)
            }
        }
    }

    @Test
    fun testCompoundTagWithEmojiAndNullRoundtrip() {
        Arena.ofConfined().use { arena ->
            val compound = Tags.compound()
            compound.putString("user_name_😀", "Alpha 😀")
            compound.putString("null_\u0000_key", "null_\u0000_val")

            val savedBytes = Totem.saveToByteArray("schematic_😀", compound)

            val (loadedName, loadedCompound) = Totem.load(savedBytes, arena)
            assertEquals("schematic_😀", loadedName)
            assertEquals("Alpha 😀", loadedCompound.getString("user_name_😀"))
            assertEquals("null_\u0000_val", loadedCompound.getString("null_\u0000_key"))

            val temporaryFile = createTempFile("totem_emoji", ".nbt")

            try {
                Totem.save("schematic_😀", compound, temporaryFile)
                val querzNamedTag = NBTUtil.read(temporaryFile.toFile())
                assertEquals("schematic_😀", querzNamedTag.name)

                val querzRoot = querzNamedTag.tag as QuerzCompoundTag
                assertEquals("Alpha 😀", querzRoot.getString("user_name_😀"))
                assertEquals("null_\u0000_val", querzRoot.getString("null_\u0000_key"))
            } finally {
                temporaryFile.deleteIfExists()
            }
        }
    }

}



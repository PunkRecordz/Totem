package org.punkrecordz.totem

import net.querz.nbt.io.NBTUtil
import org.junit.jupiter.api.Test
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.tag.Tags
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.foreign.Arena
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import net.querz.nbt.tag.CompoundTag as QuerzCompoundTag

class ModifiedUtf8Tests {

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

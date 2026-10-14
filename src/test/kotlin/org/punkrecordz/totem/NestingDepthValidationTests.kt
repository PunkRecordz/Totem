package org.punkrecordz.totem

import org.junit.jupiter.api.Test
import org.punkrecordz.totem.io.compression.CompressionType
import org.punkrecordz.totem.tag.ListTag
import org.punkrecordz.totem.tag.TagType
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NestingDepthValidationTests {

    @Test
    fun testDeeplyNestedCompoundThrowsIllegalArgumentException() {
        val depth = 10_000
        val rawOutput = ByteArrayOutputStream()

        DataOutputStream(rawOutput).use { output ->
            output.writeByte(TagType.COMPOUND.id)
            output.writeUTF("")

            repeat(depth) {
                output.writeByte(TagType.COMPOUND.id)
                output.writeUTF("a")
            }

            repeat(depth) {
                output.writeByte(TagType.END.id)
            }

            output.writeByte(TagType.SHORT.id)
            output.writeUTF("Width")
            output.writeShort(32)
            output.writeByte(TagType.END.id)
        }

        val rawBytes = rawOutput.toByteArray()

        Arena.ofConfined().use { arena ->
            val rawSegment = MemorySegment.ofArray(rawBytes)
            val gzipSegment = CompressionType.GZIP.compress(rawSegment, arena)
            assertTrue(gzipSegment.byteSize() < 200L)

            val (_, root) = Totem.load(gzipSegment, arena)

            val containsKeyException = assertFailsWith<IllegalArgumentException> {
                root.containsKey("zzz")
            }
            assertTrue(containsKeyException.message!!.contains("512"))

            assertFailsWith<IllegalArgumentException> {
                root.getShort("Width")
            }

            assertFailsWith<IllegalArgumentException> {
                root.keys
            }

            assertFailsWith<IllegalArgumentException> {
                root.size
            }

            assertFailsWith<IllegalArgumentException> {
                root.pin()
            }

            assertFailsWith<IllegalArgumentException> {
                Totem.inspect(gzipSegment, arena)
            }
        }
    }

    @Test
    fun testDeeplyNestedListThrowsIllegalArgumentException() {
        val depth = 10_000
        val rawOutput = ByteArrayOutputStream()

        DataOutputStream(rawOutput).use { output ->
            output.writeByte(TagType.COMPOUND.id)
            output.writeUTF("")

            output.writeByte(TagType.LIST.id)
            output.writeUTF("a")

            repeat(depth) {
                output.writeByte(TagType.LIST.id)
                output.writeInt(1)
            }

            output.writeByte(TagType.END.id)
            output.writeInt(0)
            output.writeByte(TagType.END.id)
        }

        val rawBytes = rawOutput.toByteArray()

        Arena.ofConfined().use { arena ->
            val (_, root) = Totem.load(rawBytes, arena, CompressionType.NONE)

            val containsKeyException = assertFailsWith<IllegalArgumentException> {
                root.containsKey("zzz")
            }
            assertTrue(containsKeyException.message!!.contains("512"))

            assertFailsWith<IllegalArgumentException> {
                root.keys
            }

            assertFailsWith<IllegalArgumentException> {
                root.pin()
            }

            val outerList = root.getList<ListTag<*>>("a", TagType.LIST)!!

            assertFailsWith<IllegalArgumentException> {
                outerList[0]
            }

            assertFailsWith<IllegalArgumentException> {
                outerList.iterator().next()
            }

            assertFailsWith<IllegalArgumentException> {
                outerList.pin()
            }

            val rawSegment = MemorySegment.ofArray(rawBytes)
            assertFailsWith<IllegalArgumentException> {
                Totem.inspect(rawSegment, arena)
            }
        }
    }

    @Test
    fun testValidNestingWithinMaxDepthSucceeds() {
        val validDepth = 512
        val rawOutput = ByteArrayOutputStream()

        DataOutputStream(rawOutput).use { output ->
            output.writeByte(TagType.COMPOUND.id)
            output.writeUTF("")

            repeat(validDepth) {
                output.writeByte(TagType.COMPOUND.id)
                output.writeUTF("a")
            }

            repeat(validDepth) {
                output.writeByte(TagType.END.id)
            }

            output.writeByte(TagType.SHORT.id)
            output.writeUTF("Width")
            output.writeShort(64)
            output.writeByte(TagType.END.id)
        }

        val rawBytes = rawOutput.toByteArray()

        Arena.ofConfined().use { arena ->
            val (_, root) = Totem.load(rawBytes, arena, CompressionType.NONE)
            assertEquals(false, root.containsKey("zzz"))
            assertEquals(64.toShort(), root.getShort("Width"))
            assertEquals(2, root.size)
            assertEquals(setOf("a", "Width"), root.keys)

            val pinned = root.pin()
            assertEquals(64.toShort(), pinned.getShort("Width"))
        }
    }

}

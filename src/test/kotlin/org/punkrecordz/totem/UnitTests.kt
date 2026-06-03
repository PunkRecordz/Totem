package org.punkrecordz.totem

import org.junit.jupiter.api.Test
import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.Tags
import org.punkrecordz.totem.impl.native.NativeByteArrayTag
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

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
}

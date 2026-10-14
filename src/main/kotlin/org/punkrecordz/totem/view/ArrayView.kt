package org.punkrecordz.totem.view

interface ArrayView {

    val size: Int

    fun isEmpty(): Boolean = size == 0

    fun isNotEmpty(): Boolean = size > 0

}

fun ArrayView.contentEquals(other: ArrayView): Boolean {
    if (this === other) return true
    if (this.size != other.size) return false

    return when (this) {
        is ByteView -> other is ByteView && this.contentEquals(other)
        is ShortView -> other is ShortView && this.contentEquals(other)
        is IntView -> other is IntView && this.contentEquals(other)
        is LongView -> other is LongView && this.contentEquals(other)
        else -> false
    }
}

private fun NativeView.nativePayloadEquals(
    other: NativeView,
    byteCount: Long,
): Boolean = this.segment.asSlice(4L, byteCount).mismatch(other.segment.asSlice(4L, byteCount)) == -1L

fun ByteView.contentEquals(other: ByteView): Boolean {
    if (this === other) return true
    if (size != other.size) return false

    if (this is NativeView && other is NativeView) {
        return this.nativePayloadEquals(other, size.toLong())
    }

    for (index in 0 until size) {
        if (this[index] != other[index]) return false
    }

    return true
}

fun ShortView.contentEquals(other: ShortView): Boolean {
    if (this === other) return true
    if (size != other.size) return false

    if (this is NativeView && other is NativeView) {
        return this.nativePayloadEquals(other, size.toLong() * Short.SIZE_BYTES)
    }

    for (index in 0 until size) {
        if (this[index] != other[index]) return false
    }

    return true
}

fun IntView.contentEquals(other: IntView): Boolean {
    if (this === other) return true
    if (size != other.size) return false

    if (this is NativeView && other is NativeView) {
        return this.nativePayloadEquals(other, size.toLong() * Int.SIZE_BYTES)
    }

    for (index in 0 until size) {
        if (this[index] != other[index]) return false
    }

    return true
}

fun LongView.contentEquals(other: LongView): Boolean {
    if (this === other) return true
    if (size != other.size) return false

    if (this is NativeView && other is NativeView) {
        return this.nativePayloadEquals(other, size.toLong() * Long.SIZE_BYTES)
    }

    for (index in 0 until size) {
        if (this[index] != other[index]) return false
    }

    return true
}

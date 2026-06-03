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

fun ByteView.contentEquals(other: ByteView): Boolean {
    if (this === other) return true
    if (size != other.size) return false

    if (this is NativeView && other is NativeView) {
        val bytes = size.toLong()

        return this.segment.asSlice(4L, bytes).mismatch(other.segment.asSlice(4L, bytes)) == -1L
    }

    for (i in 0 until size) {
        if (this[i] != other[i]) return false
    }

    return true
}

fun ShortView.contentEquals(other: ShortView): Boolean {
    if (this === other) return true
    if (size != other.size) return false

    if (this is NativeView && other is NativeView) {
        val bytes = size.toLong() * 2L

        return this.segment.asSlice(4L, bytes).mismatch(other.segment.asSlice(4L, bytes)) == -1L
    }

    for (i in 0 until size) {
        if (this[i] != other[i]) return false
    }

    return true
}

fun IntView.contentEquals(other: IntView): Boolean {
    if (this === other) return true
    if (size != other.size) return false

    if (this is NativeView && other is NativeView) {
        val bytes = size.toLong() * 4L

        return this.segment.asSlice(4L, bytes).mismatch(other.segment.asSlice(4L, bytes)) == -1L
    }

    for (i in 0 until size) {
        if (this[i] != other[i]) return false
    }

    return true
}

fun LongView.contentEquals(other: LongView): Boolean {
    if (this === other) return true
    if (size != other.size) return false

    if (this is NativeView && other is NativeView) {
        val bytes = size.toLong() * 8L

        return this.segment.asSlice(4L, bytes).mismatch(other.segment.asSlice(4L, bytes)) == -1L
    }

    for (i in 0 until size) {
        if (this[i] != other[i]) return false
    }

    return true
}

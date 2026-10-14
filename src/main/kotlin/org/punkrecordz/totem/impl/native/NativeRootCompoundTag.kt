package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.tag.CompoundTag
import org.punkrecordz.totem.view.NativeView
import java.lang.foreign.MemorySegment

class NativeRootCompoundTag(
    val rootSegment: MemorySegment,
    val rootName: String,
    val contentTag: NativeCompoundTag,
) : CompoundTag by contentTag, NativeView {

    override val segment: MemorySegment
        get() = contentTag.segment

    override fun pin(): CompoundTag = contentTag.pin()

    override fun copy(): CompoundTag = contentTag.copy()

    override fun equals(other: Any?): Boolean {
        if (other is NativeRootCompoundTag) {
            return contentTag == other.contentTag
        }

        return contentTag == other
    }

    override fun hashCode(): Int = contentTag.hashCode()

    override fun toString(): String = contentTag.toString()

}

package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.tag.CompoundTag
import org.punkrecordz.totem.view.NativeView
import java.lang.foreign.MemorySegment

/**
 * Root off-heap view over a fully serialized Compound NBT structure including type and name headers.
 *
 * Strategy:
 * When a root Compound tag is loaded off-heap from an NBT stream, the entire decompressed segment
 * already contains the complete binary NBT format (1-byte type ID, 2-byte name length, name UTF-8 bytes,
 * and payload entries). By capturing both the full segment and the inner content tag, modifications applied
 * in-place to child tags directly modify the underlying [rootSegment]. During serialization, this allows
 * Totem to bypass re-serializing and copying hundreds of megabytes of NBT data into temporary scratch buffers,
 * feeding [rootSegment] directly into compression pipelines.
 *
 * Trade-offs:
 * Holds a reference to the entire root memory segment of the loaded NBT file. Modifications to the tag's
 * name require falling back to standard serialization since the header size may differ.
 *
 * Performance targets:
 * Zero-copy and zero-allocation root tag serialization for in-place data transformations.
 */
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

package org.punkrecordz.totem.view

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

internal const val MAX_VARINT_PARTITIONS: Int = 64

internal class VarIntEncodeScratch {

    val partitionSizes: MemorySegment = Arena.ofAuto().allocate(ValueLayout.JAVA_LONG, MAX_VARINT_PARTITIONS.toLong())
    val partitionCountOut: MemorySegment = Arena.ofAuto().allocate(ValueLayout.JAVA_INT)
    val chunkSizeOut: MemorySegment = Arena.ofAuto().allocate(ValueLayout.JAVA_LONG)

    companion object {

        private val threadLocalScratch: ThreadLocal<VarIntEncodeScratch> = ThreadLocal.withInitial {
            VarIntEncodeScratch()
        }

        fun get(): VarIntEncodeScratch = threadLocalScratch.get()

    }

}

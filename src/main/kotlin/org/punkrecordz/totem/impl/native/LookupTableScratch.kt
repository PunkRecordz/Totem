package org.punkrecordz.totem.impl.native

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

internal const val LOOKUP_TABLE_ENTRIES: Int = 65536
internal const val LOOKUP_TABLE_BYTES: Long = LOOKUP_TABLE_ENTRIES.toLong() * Short.SIZE_BYTES

internal class LookupTableScratch {

    val segment: MemorySegment = Arena.ofAuto().allocate(LOOKUP_TABLE_BYTES, 64L)
    var dirtyEntries: Int = 0

    companion object {

        private val threadLocalScratch: ThreadLocal<LookupTableScratch> = ThreadLocal.withInitial {
            LookupTableScratch()
        }

        fun get(): LookupTableScratch = threadLocalScratch.get()

    }

}

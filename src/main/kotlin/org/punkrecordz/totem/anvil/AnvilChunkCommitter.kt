package org.punkrecordz.totem.anvil

import java.lang.foreign.MemorySegment

internal object AnvilChunkCommitter {

    fun commit(
        state: AnvilRegionState,
        sectorTable: AnvilSectorTable,
        chunkIndex: Int,
        framedSectors: MemorySegment,
    ) {
        val byteSize = framedSectors.byteSize()
        val requiredSectors = (byteSize / AnvilSectorTable.SECTOR_SIZE).toInt()

        when (state) {
            is AnvilRegionState.SequentialBufferedWrite -> {
                state.appendFramedSectors(sectorTable, chunkIndex, framedSectors, requiredSectors)
            }
            is AnvilRegionState.InPlaceWrite -> {
                val sectorOffset = sectorTable.allocateChunkSectors(chunkIndex, requiredSectors)
                val fileOffset = sectorOffset.toLong() * AnvilSectorTable.SECTOR_SIZE.toLong()

                AnvilChunkEncodeScratch.get().writeExternalSectorsToChannel(
                    fileChannel = state.fileChannel,
                    framedSectors = framedSectors,
                    fileOffset = fileOffset,
                )
                state.actualFileSize = maxOf(state.actualFileSize, fileOffset + byteSize)
                state.headerDirty = true
            }
            is AnvilRegionState.ReadOnlyLoaded -> throw IllegalStateException(
                "Cannot commit chunk $chunkIndex: the region was opened read-only",
            )
            AnvilRegionState.Closed -> throw IllegalStateException("AnvilRegionFile is closed.")
        }
    }

}

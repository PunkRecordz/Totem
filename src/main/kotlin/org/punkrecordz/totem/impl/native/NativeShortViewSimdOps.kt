package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.concurrent.TotemThreadPool
import org.punkrecordz.totem.concurrent.awaitResult
import org.punkrecordz.totem.ffi.TotemParallelSys
import org.punkrecordz.totem.ffi.TotemSys
import org.punkrecordz.totem.io.allocateUninitialized
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.Callable

internal const val SHORT_VIEW_HEADER_OFFSET: Long = 4L
internal const val SHORT_VIEW_PARALLEL_THRESHOLD: Long = 65536L

internal object NativeShortViewSimdOps {

    fun replaceAll(
        payloadSlice: MemorySegment,
        elementCount: Long,
        target: Short,
        replacement: Short,
    ) {
        if (elementCount < SHORT_VIEW_PARALLEL_THRESHOLD) {
            TotemSys.replaceShorts(payloadSlice, elementCount, target, replacement)
            return
        }

        TotemParallelSys.replaceShortsParallel(payloadSlice, elementCount, target, replacement)
    }

    fun fill(
        payloadSlice: MemorySegment,
        elementCount: Long,
        value: Short,
    ) {
        if (elementCount < SHORT_VIEW_PARALLEL_THRESHOLD) {
            TotemSys.fillShorts(payloadSlice, elementCount, value)
            return
        }

        TotemParallelSys.fillShortsParallel(payloadSlice, elementCount, value)
    }

    fun translate(
        payloadSlice: MemorySegment,
        elementCount: Long,
        lookupTable: ShortArray,
    ) {
        if (elementCount == 0L || lookupTable.isEmpty()) {
            return
        }

        val scratch = LookupTableScratch.get()
        val copyCount = minOf(LOOKUP_TABLE_ENTRIES, lookupTable.size)
        val copiedBytes = copyCount.toLong() * Short.SIZE_BYTES

        MemorySegment.copy(
            MemorySegment.ofArray(lookupTable),
            0L,
            scratch.segment,
            0L,
            copiedBytes,
        )

        if (copyCount < scratch.dirtyEntries) {
            val staleBytes = (scratch.dirtyEntries - copyCount).toLong() * Short.SIZE_BYTES
            scratch.segment.asSlice(copiedBytes, staleBytes).fill(0)
        }
        scratch.dirtyEntries = copyCount

        if (elementCount < SHORT_VIEW_PARALLEL_THRESHOLD) {
            TotemSys.translateShorts(payloadSlice, elementCount, scratch.segment)
            return
        }

        TotemParallelSys.translateShortsParallel(payloadSlice, elementCount, scratch.segment)
    }

    fun scanMatchingIndices(
        payloadSlice: MemorySegment,
        elementCount: Long,
        shouldIgnore: BooleanArray,
    ): IntArray {
        if (elementCount == 0L || shouldIgnore.isEmpty()) {
            return IntArray(0)
        }

        return Arena.ofConfined().use { temporaryArena ->
            val lookupTableSegment = temporaryArena.allocate(LOOKUP_TABLE_ENTRIES.toLong())
            val lookupTableBytes = ByteArray(LOOKUP_TABLE_ENTRIES) { index ->
                if (index < shouldIgnore.size && shouldIgnore[index]) 1.toByte() else 0.toByte()
            }

            MemorySegment.copy(
                MemorySegment.ofArray(lookupTableBytes),
                0L,
                lookupTableSegment,
                0L,
                LOOKUP_TABLE_ENTRIES.toLong(),
            )

            if (elementCount < SHORT_VIEW_PARALLEL_THRESHOLD) {
                val matchCount = TotemSys.countMatchingShorts(payloadSlice, elementCount, lookupTableSegment)
                if (matchCount == 0L) {
                    return@use IntArray(0)
                }

                val matchesSegment = temporaryArena.allocateUninitialized(matchCount * Int.SIZE_BYTES)
                TotemSys.scanMatchingShorts(
                    payloadSlice,
                    elementCount,
                    0L,
                    lookupTableSegment,
                    matchesSegment,
                    matchCount,
                )

                val matchedIndices = IntArray(matchCount.toInt())
                MemorySegment.copy(matchesSegment, ValueLayout.JAVA_INT, 0L, matchedIndices, 0, matchCount.toInt())

                return@use matchedIndices
            }

            val availableProcessors = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
            val chunkSize = (elementCount + availableProcessors - 1) / availableProcessors
            val partitionCount = ((elementCount + chunkSize - 1) / chunkSize).toInt()
            val rawBaseAddress = payloadSlice.address()
            val rawLookupTableAddress = lookupTableSegment.address()

            val countTasks = (0 until partitionCount).map { partitionIndex ->
                val startOffset = partitionIndex.toLong() * chunkSize
                val count = minOf(chunkSize, elementCount - startOffset)

                Callable {
                    val workerSlice = MemorySegment.ofAddress(rawBaseAddress + (startOffset shl 1))
                        .reinterpret(count shl 1)
                    val workerLookupSlice = MemorySegment.ofAddress(rawLookupTableAddress)
                        .reinterpret(LOOKUP_TABLE_ENTRIES.toLong())

                    TotemSys.countMatchingShorts(workerSlice, count, workerLookupSlice)
                }
            }

            val countFutures = TotemThreadPool.executor.invokeAll(countTasks)
            val partitionCounts = LongArray(partitionCount) { index -> countFutures[index].awaitResult() }
            val totalMatches = partitionCounts.sum()

            if (totalMatches == 0L) {
                return@use IntArray(0)
            }

            if (totalMatches > Int.MAX_VALUE) {
                throw IllegalStateException(
                    "Matching elements count ($totalMatches) exceeds maximum array capacity of ${Int.MAX_VALUE}",
                )
            }

            val matchesSegment = temporaryArena.allocateUninitialized(totalMatches * Int.SIZE_BYTES)
            val rawMatchesAddress = matchesSegment.address()
            val partitionDestinationOffsets = LongArray(partitionCount)
            var currentOffset = 0L

            for (index in 0 until partitionCount) {
                partitionDestinationOffsets[index] = currentOffset
                currentOffset += partitionCounts[index]
            }

            val scanTasks = (0 until partitionCount).map { partitionIndex ->
                val startOffset = partitionIndex.toLong() * chunkSize
                val count = minOf(chunkSize, elementCount - startOffset)
                val matchesInPartition = partitionCounts[partitionIndex]
                val destinationOffset = partitionDestinationOffsets[partitionIndex]

                Callable {
                    if (matchesInPartition > 0L) {
                        val workerSlice = MemorySegment.ofAddress(rawBaseAddress + (startOffset shl 1))
                            .reinterpret(count shl 1)
                        val workerLookupSlice = MemorySegment.ofAddress(rawLookupTableAddress)
                            .reinterpret(LOOKUP_TABLE_ENTRIES.toLong())
                        val workerDestinationSlice = MemorySegment.ofAddress(rawMatchesAddress + (destinationOffset shl 2))
                            .reinterpret(matchesInPartition shl 2)

                        TotemSys.scanMatchingShorts(
                            workerSlice,
                            count,
                            startOffset,
                            workerLookupSlice,
                            workerDestinationSlice,
                            matchesInPartition,
                        )
                    }
                }
            }

            val scanFutures = TotemThreadPool.executor.invokeAll(scanTasks)
            for (future in scanFutures) {
                future.awaitResult()
            }

            val matchedIndices = IntArray(totalMatches.toInt())
            MemorySegment.copy(matchesSegment, ValueLayout.JAVA_INT, 0L, matchedIndices, 0, totalMatches.toInt())

            matchedIndices
        }
    }

}

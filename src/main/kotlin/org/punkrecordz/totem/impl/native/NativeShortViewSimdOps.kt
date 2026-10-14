package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.concurrent.TotemThreadPool
import org.punkrecordz.totem.ffi.TotemSys
import org.punkrecordz.totem.io.allocateUninitialized
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.Callable

internal const val SHORT_VIEW_HEADER_OFFSET: Long = 4L
internal const val SHORT_VIEW_PARALLEL_THRESHOLD: Long = 65536L

internal object NativeShortViewSimdOps {

    inline fun executeParallel(
        elementCount: Long,
        payloadSlice: MemorySegment,
        crossinline action: (workerSlice: MemorySegment, count: Long) -> Unit,
    ) {
        if (elementCount < SHORT_VIEW_PARALLEL_THRESHOLD) {
            action(payloadSlice, elementCount)
            return
        }

        val availableProcessors = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
        val chunkSize = (elementCount + availableProcessors - 1) / availableProcessors
        val partitionCount = ((elementCount + chunkSize - 1) / chunkSize).toInt()
        val rawBaseAddress = payloadSlice.address()

        val tasks = (0 until partitionCount).map { partitionIndex ->
            val startOffset = partitionIndex.toLong() * chunkSize
            val count = minOf(chunkSize, elementCount - startOffset)

            Callable {
                val workerSlice = MemorySegment.ofAddress(rawBaseAddress + (startOffset shl 1))
                    .reinterpret(count shl 1)

                action(workerSlice, count)
            }
        }

        val futures = TotemThreadPool.executor.invokeAll(tasks)
        for (future in futures) {
            future.get()
        }
    }

    fun translate(
        payloadSlice: MemorySegment,
        elementCount: Long,
        lookupTable: ShortArray,
    ) {
        if (elementCount == 0L || lookupTable.isEmpty()) return

        Arena.ofConfined().use { tempArena ->
            val lookupTableSegment = tempArena.allocate(65536L * Short.SIZE_BYTES)
            val copyCount = minOf(65536, lookupTable.size)
            MemorySegment.copy(
                MemorySegment.ofArray(lookupTable),
                0L,
                lookupTableSegment,
                0L,
                copyCount.toLong() * Short.SIZE_BYTES,
            )

            val rawLookupTableAddress = lookupTableSegment.address()

            executeParallel(elementCount, payloadSlice) { workerSlice, count ->
                val workerLookupTableSlice = MemorySegment.ofAddress(rawLookupTableAddress)
                    .reinterpret(65536L * Short.SIZE_BYTES)

                TotemSys.translateShorts(
                    workerSlice,
                    count,
                    workerLookupTableSlice,
                )
            }
        }
    }

    fun scanMatchingIndices(
        payloadSlice: MemorySegment,
        elementCount: Long,
        shouldIgnore: BooleanArray,
    ): IntArray {
        if (elementCount == 0L || shouldIgnore.isEmpty()) return IntArray(0)

        return Arena.ofConfined().use { tempArena ->
            val lookupTableSegment = tempArena.allocate(65536L)
            val lookupTableBytes = ByteArray(65536) { index ->
                if (index < shouldIgnore.size && shouldIgnore[index]) 1.toByte() else 0.toByte()
            }

            MemorySegment.copy(
                MemorySegment.ofArray(lookupTableBytes),
                0L,
                lookupTableSegment,
                0L,
                65536L,
            )

            if (elementCount < SHORT_VIEW_PARALLEL_THRESHOLD) {
                val matchCount = TotemSys.countMatchingShorts(payloadSlice, elementCount, lookupTableSegment)
                if (matchCount == 0L) return@use IntArray(0)

                val resultSegment = tempArena.allocateUninitialized(matchCount * 4L)

                TotemSys.scanMatchingShorts(
                    payloadSlice,
                    elementCount,
                    0L,
                    lookupTableSegment,
                    resultSegment,
                    matchCount,
                )

                val result = IntArray(matchCount.toInt())
                MemorySegment.copy(resultSegment, ValueLayout.JAVA_INT, 0L, result, 0, matchCount.toInt())

                return@use result
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
                    val workerLookupTableSlice = MemorySegment.ofAddress(rawLookupTableAddress)
                        .reinterpret(65536L)

                    TotemSys.countMatchingShorts(
                        workerSlice,
                        count,
                        workerLookupTableSlice,
                    )
                }
            }

            val countFutures = TotemThreadPool.executor.invokeAll(countTasks)
            val partitionCounts = LongArray(partitionCount) { index -> countFutures[index].get() }
            val totalMatches = partitionCounts.sum()

            if (totalMatches == 0L) {
                return@use IntArray(0)
            }

            if (totalMatches > Int.MAX_VALUE) {
                throw IllegalStateException("Matching elements count ($totalMatches) exceeds maximum array capacity of ${Int.MAX_VALUE}")
            }

            val resultSegment = tempArena.allocateUninitialized(totalMatches * 4L)
            val rawResultAddress = resultSegment.address()

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
                        val workerLookupTableSlice = MemorySegment.ofAddress(rawLookupTableAddress)
                            .reinterpret(65536L)
                        val workerDestinationSlice = MemorySegment.ofAddress(rawResultAddress + (destinationOffset shl 2))
                            .reinterpret(matchesInPartition shl 2)

                        TotemSys.scanMatchingShorts(
                            workerSlice,
                            count,
                            startOffset,
                            workerLookupTableSlice,
                            workerDestinationSlice,
                            matchesInPartition,
                        )
                    }
                }
            }

            val scanFutures = TotemThreadPool.executor.invokeAll(scanTasks)
            for (future in scanFutures) {
                future.get()
            }

            val result = IntArray(totalMatches.toInt())
            MemorySegment.copy(resultSegment, ValueLayout.JAVA_INT, 0L, result, 0, totalMatches.toInt())

            result
        }
    }

}

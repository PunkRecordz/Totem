package org.punkrecordz.totem.io.compression

import com.fulcrumgenomics.jlibdeflate.LibdeflateCompressor
import org.punkrecordz.totem.concurrent.TotemThreadPool
import org.punkrecordz.totem.concurrent.awaitResult
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.io.memory.OffHeapScratchBuffer
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger

private val gzipCompressors = ThreadLocal.withInitial { HashMap<Int, LibdeflateCompressor>() }

private const val PARALLEL_THRESHOLD_BYTES: Long = 256 * 1024L
private const val MIN_CHUNK_SIZE_BYTES: Long = 128 * 1024L
private const val MAX_CHUNK_SIZE_BYTES: Long = 4 * 1024 * 1024L

class GzipCompression(val level: Int = 1) : Compression {

    override fun decompress(
        input: MemorySegment,
        arena: Arena,
    ): MemorySegment = GzipParallelDecompressor.decompress(input, arena)

    override fun compress(
        input: MemorySegment,
        arena: Arena,
    ): MemorySegment {
        val inputSize = input.byteSize()
        val availableProcessors = Runtime.getRuntime().availableProcessors()

        if (availableProcessors <= 1 || inputSize < PARALLEL_THRESHOLD_BYTES) {
            return compressSequential(input, arena)
        }

        val chunks = mutableListOf<MemorySegment>()
        appendSplitChunks(input, availableProcessors, chunks)

        if (chunks.size <= 1) {
            return compressSequential(input, arena)
        }

        return compressChunksParallel(chunks, arena)
    }

    override fun compress(
        slices: List<MemorySegment>,
        arena: Arena,
    ): MemorySegment {
        if (slices.isEmpty()) {
            return arena.allocate(0L)
        }

        if (slices.size == 1) {
            return compress(slices[0], arena)
        }

        val totalInputSize = slices.sumOf { slice -> slice.byteSize() }
        val availableProcessors = Runtime.getRuntime().availableProcessors()

        if (availableProcessors <= 1 || totalInputSize < PARALLEL_THRESHOLD_BYTES) {
            val compressor = gzipCompressors.get().getOrPut(level) {
                LibdeflateCompressor(level)
            }

            val totalBound = slices.sumOf { slice ->
                compressor.gzipCompressBound(slice.byteSize().toInt()).toLong()
            }
            val scratchBuffer = OffHeapScratchBuffer.getCompressionBuffer()
            val scratchSegment = scratchBuffer.acquire(totalBound)
            var currentOutputOffset = 0L

            for (slice in slices) {
                if (slice.byteSize() == 0L) continue
                val inputBuffer = slice.asByteBuffer()
                val targetSlice = scratchSegment.asSlice(currentOutputOffset)
                val compressedBytes = compressor.gzipCompress(inputBuffer, targetSlice.asByteBuffer())
                currentOutputOffset += compressedBytes
            }

            val outputSegment = arena.allocateUninitialized(currentOutputOffset)
            MemorySegment.copy(scratchSegment, 0L, outputSegment, 0L, currentOutputOffset)

            return outputSegment
        }

        val chunks = mutableListOf<MemorySegment>()

        for (slice in slices) {
            val sliceSize = slice.byteSize()
            if (sliceSize == 0L) continue

            if (sliceSize < PARALLEL_THRESHOLD_BYTES) {
                chunks.add(slice)
            } else {
                appendSplitChunks(slice, availableProcessors, chunks)
            }
        }

        return compressChunksParallel(chunks, arena)
    }

    private fun appendSplitChunks(
        slice: MemorySegment,
        availableProcessors: Int,
        destinationChunks: MutableList<MemorySegment>,
    ) {
        val sliceSize = slice.byteSize()
        val maxAllowedChunkSize = maxOf(MAX_CHUNK_SIZE_BYTES, (sliceSize + 511L) / 512L)
        val idealChunkSize = (sliceSize / (availableProcessors * 2)).coerceIn(MIN_CHUNK_SIZE_BYTES, maxAllowedChunkSize)
        val partitionCount = maxOf(1, ((sliceSize + idealChunkSize - 1L) / idealChunkSize).toInt())
        val chunkSize = (sliceSize + partitionCount - 1L) / partitionCount

        for (chunkIndex in 0 until partitionCount) {
            val chunkStart = chunkIndex * chunkSize
            val chunkLength = minOf(chunkSize, sliceSize - chunkStart)
            if (chunkLength > 0L) {
                destinationChunks.add(slice.asSlice(chunkStart, chunkLength))
            }
        }
    }

    private fun compressSequential(
        input: MemorySegment,
        arena: Arena,
    ): MemorySegment {
        val compressor = gzipCompressors.get().getOrPut(level) {
            LibdeflateCompressor(level)
        }

        val bound = compressor.gzipCompressBound(input.byteSize().toInt()).toLong()
        val scratchSegment = OffHeapScratchBuffer.getCompressionBuffer().acquire(bound)

        val inputBuffer = input.asByteBuffer()
        val outputBuffer = scratchSegment.asByteBuffer()
        val compressedSize = compressor.gzipCompress(inputBuffer, outputBuffer).toLong()

        val output = arena.allocateUninitialized(compressedSize)
        MemorySegment.copy(scratchSegment, 0L, output, 0L, compressedSize)

        return output
    }

    private fun compressChunksParallel(
        chunks: List<MemorySegment>,
        arena: Arena,
    ): MemorySegment {
        val chunkCount = chunks.size
        val availableProcessors = Runtime.getRuntime().availableProcessors()
        val workerCount = minOf(availableProcessors, chunkCount)
        val borrowedBuffers = Array(workerCount) { OffHeapScratchBuffer.borrowCompressionBuffer() }

        try {
            val chunkWorkerIndices = IntArray(chunkCount)
            val chunkBufferOffsets = LongArray(chunkCount)
            val chunkCompressedLengths = IntArray(chunkCount)
            val nextChunkIndex = AtomicInteger(0)

            val tasks = (0 until workerCount).map { workerIndex ->
                val workerScratchBuffer = borrowedBuffers[workerIndex]

                Callable {
                    val threadCompressor = gzipCompressors.get().getOrPut(level) {
                        LibdeflateCompressor(level)
                    }
                    var currentWorkerOffset = 0L

                    while (true) {
                        val chunkIndex = nextChunkIndex.getAndIncrement()
                        if (chunkIndex >= chunkCount) {
                            break
                        }

                        val chunkSlice = chunks[chunkIndex]
                        val chunkBound = threadCompressor.gzipCompressBound(chunkSlice.byteSize().toInt()).toLong()
                        val targetScratchSlice = workerScratchBuffer.acquireAt(currentWorkerOffset, chunkBound)

                        val compressedBytes = threadCompressor.gzipCompress(
                            chunkSlice.asByteBuffer(),
                            targetScratchSlice.asByteBuffer(),
                        )

                        if (compressedBytes <= 0) {
                            throw IllegalStateException(
                                "Failed to compress GZIP chunk of size ${chunkSlice.byteSize()} even with theoretical bound $chunkBound",
                            )
                        }

                        chunkWorkerIndices[chunkIndex] = workerIndex
                        chunkBufferOffsets[chunkIndex] = currentWorkerOffset
                        chunkCompressedLengths[chunkIndex] = compressedBytes
                        currentWorkerOffset += compressedBytes.toLong()
                    }
                }
            }

            val futures = TotemThreadPool.executor.invokeAll(tasks)
            for (future in futures) {
                future.awaitResult()
            }

            var totalCompressedSize = 0L
            for (chunkIndex in 0 until chunkCount) {
                totalCompressedSize += chunkCompressedLengths[chunkIndex].toLong()
            }

            val finalOutput = arena.allocateUninitialized(totalCompressedSize)
            var currentDestinationOffset = 0L

            for (chunkIndex in 0 until chunkCount) {
                val workerIndex = chunkWorkerIndices[chunkIndex]
                val sourceOffset = chunkBufferOffsets[chunkIndex]
                val compressedLength = chunkCompressedLengths[chunkIndex].toLong()
                val compressedSlice = borrowedBuffers[workerIndex].acquireAt(sourceOffset, compressedLength)

                MemorySegment.copy(
                    compressedSlice,
                    0L,
                    finalOutput,
                    currentDestinationOffset,
                    compressedLength,
                )

                currentDestinationOffset += compressedLength
            }

            return finalOutput
        } finally {
            for (buffer in borrowedBuffers) {
                OffHeapScratchBuffer.releaseCompressionBuffer(buffer)
            }
        }
    }

}

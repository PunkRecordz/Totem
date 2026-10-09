package org.punkrecordz.totem.impl.native

import org.punkrecordz.totem.concurrent.TotemThreadPool
import org.punkrecordz.totem.ffi.TotemSys
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.io.allocateUninitialized
import org.punkrecordz.totem.view.NativeView
import org.punkrecordz.totem.view.ShortView
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.Callable

private const val HEADER_OFFSET: Long = 4L
private const val PARALLEL_THRESHOLD_ELEMENTS: Long = 65536L

/**
 * Zero-allocation off-heap view over a continuous sequence of 16-bit short integers.
 *
 * Strategy:
 * Uses a Kotlin [JvmInline] value class wrapping a raw [MemorySegment]. At runtime, HotSpot erases
 * this wrapper to a pure 64-bit reference, avoiding JVM object headers, garbage collection pressure,
 * and heap indirection. Aligned 2-byte access and parallel AVX2 SIMD routines deliver maximum throughput
 * for block manipulation pipelines.
 *
 * Trade-offs:
 * Off-heap segments must be bound to a valid [Arena] lifecycle. Accessing memory after arena closure
 * causes compile-enforced or runtime safe illegal state failures rather than silent memory corruption.
 *
 * Performance targets:
 * Direct memory instructions on aligned 2-byte short values matching native memory endianness,
 * with sub-millisecond batch replacements across millions of block states.
 */
@JvmInline
value class NativeShortView(
    override val segment: MemorySegment,
) : ShortView, NativeView {

    override val size: Int
        get() = segment.get(MemoryLayouts.INT, 0L)

    override operator fun get(index: Int): Short {
        return segment.get(
            NATIVE_SHORT,
            HEADER_OFFSET + (index.toLong() shl 1),
        )
    }

    override operator fun set(index: Int, value: Short) {
        segment.set(
            NATIVE_SHORT,
            HEADER_OFFSET + (index.toLong() shl 1),
            value,
        )
    }

    override fun replaceAll(
        target: Short,
        replacement: Short,
    ) {
        val elementCount = size.toLong()
        if (elementCount == 0L) return

        val payloadSlice = segment.asSlice(HEADER_OFFSET)

        // sequential fast path for smaller datasets
        if (elementCount < PARALLEL_THRESHOLD_ELEMENTS) {
            TotemSys.replaceShorts(
                payloadSlice,
                elementCount,
                target,
                replacement,
            )
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

                TotemSys.replaceShorts(
                    workerSlice,
                    count,
                    target,
                    replacement,
                )
            }
        }

        val futures = TotemThreadPool.executor.invokeAll(tasks)
        for (future in futures) {
            future.get()
        }
    }

    override fun fill(value: Short) {
        val elementCount = size.toLong()
        if (elementCount == 0L) return

        val payloadSlice = segment.asSlice(HEADER_OFFSET)

        // sequential fast path for smaller datasets
        if (elementCount < PARALLEL_THRESHOLD_ELEMENTS) {
            TotemSys.fillShorts(
                payloadSlice,
                elementCount,
                value,
            )
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

                TotemSys.fillShorts(
                    workerSlice,
                    count,
                    value,
                )
            }
        }

        val futures = TotemThreadPool.executor.invokeAll(tasks)
        for (future in futures) {
            future.get()
        }
    }

    override fun copy(): NativeShortView {
        val newSegment = Arena.ofAuto().allocateUninitialized(segment.byteSize())
        MemorySegment.copy(segment, 0L, newSegment, 0L, segment.byteSize())

        return NativeShortView(newSegment)
    }

    companion object {

        val NATIVE_SHORT: ValueLayout.OfShort = ValueLayout.JAVA_SHORT

        fun of(array: ShortArray, arena: Arena): NativeShortView {
            val byteSize = MemoryLayouts.INT.byteSize() + (array.size.toLong() * MemoryLayouts.SHORT.byteSize())
            val segment = arena.allocateUninitialized(byteSize)
            segment.set(MemoryLayouts.INT, 0L, array.size)

            val destinationOffset = MemoryLayouts.INT.byteSize()
            MemorySegment.copy(
                MemorySegment.ofArray(array),
                0L,
                segment,
                destinationOffset,
                array.size.toLong() * Short.SIZE_BYTES,
            )

            return NativeShortView(segment)
        }

        fun of(size: Int, arena: Arena): NativeShortView {
            val byteSize = MemoryLayouts.INT.byteSize() + (size.toLong() * MemoryLayouts.SHORT.byteSize())
            val segment = arena.allocateUninitialized(byteSize)
            segment.set(MemoryLayouts.INT, 0L, size)

            return NativeShortView(segment)
        }

    }

}

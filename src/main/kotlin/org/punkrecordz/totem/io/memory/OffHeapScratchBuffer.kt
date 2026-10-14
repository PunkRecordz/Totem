package org.punkrecordz.totem.io.memory

import org.punkrecordz.totem.io.allocateUninitialized
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentLinkedQueue

class OffHeapScratchBuffer(initialCapacity: Long = 16L * 1024L * 1024L) {

    private var currentArena: Arena = Arena.ofAuto()
    private var currentSegment: MemorySegment = currentArena.allocateUninitialized(initialCapacity)

    fun acquire(requiredBytes: Long): MemorySegment = acquireAt(0L, requiredBytes)

    fun acquireAt(
        offset: Long,
        requiredBytes: Long,
    ): MemorySegment {
        val minimumCapacity = offset + requiredBytes

        if (currentSegment.byteSize() < minimumCapacity) {
            val newCapacity = maxOf(minimumCapacity, currentSegment.byteSize() * 2L)
            val newArena = Arena.ofAuto()
            val newSegment = newArena.allocateUninitialized(newCapacity)

            if (offset > 0L) {
                MemorySegment.copy(currentSegment, 0L, newSegment, 0L, offset)
            }

            currentArena = newArena
            currentSegment = newSegment
        }

        return currentSegment.asSlice(offset, requiredBytes)
    }

    companion object {

        private val serializationBuffers: ThreadLocal<OffHeapScratchBuffer> = ThreadLocal.withInitial {
            OffHeapScratchBuffer(4L * 1024L * 1024L)
        }

        private val compressionBuffers: ThreadLocal<OffHeapScratchBuffer> = ThreadLocal.withInitial {
            OffHeapScratchBuffer(16L * 1024L * 1024L)
        }

        private val compressionBufferPool: ConcurrentLinkedQueue<OffHeapScratchBuffer> = ConcurrentLinkedQueue()

        fun getSerializationBuffer(): OffHeapScratchBuffer = serializationBuffers.get()

        fun getCompressionBuffer(): OffHeapScratchBuffer = compressionBuffers.get()

        fun borrowCompressionBuffer(): OffHeapScratchBuffer =
            compressionBufferPool.poll() ?: OffHeapScratchBuffer(16L * 1024L * 1024L)

        fun releaseCompressionBuffer(buffer: OffHeapScratchBuffer) {
            compressionBufferPool.offer(buffer)
        }

    }

}

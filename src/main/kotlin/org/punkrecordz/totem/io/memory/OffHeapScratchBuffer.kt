package org.punkrecordz.totem.io.memory

import org.punkrecordz.totem.io.allocateUninitialized
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class OffHeapScratchBuffer(initialCapacity: Long = 16L * 1024L * 1024L) {

    private var currentArena: Arena = Arena.ofAuto()
    private var currentSegment: MemorySegment = currentArena.allocateUninitialized(initialCapacity)

    fun acquire(requiredBytes: Long): MemorySegment = acquireAt(0L, requiredBytes)

    fun acquireAt(
        offset: Long,
        requiredBytes: Long,
    ): MemorySegment {
        require(offset >= 0L && requiredBytes >= 0L) {
            "Scratch slice range must be non-negative: offset=$offset, requiredBytes=$requiredBytes"
        }

        val minimumCapacity = Math.addExact(offset, requiredBytes)
        if (currentSegment.byteSize() >= minimumCapacity) {
            return currentSegment.asSlice(offset, requiredBytes)
        }

        if (minimumCapacity > MAX_RETAINED_BYTES) {
            val transientSegment = Arena.ofAuto().allocateUninitialized(minimumCapacity)
            if (offset > 0L) {
                MemorySegment.copy(currentSegment, 0L, transientSegment, 0L, offset)
            }

            return transientSegment.asSlice(offset, requiredBytes)
        }

        val doubledCapacity = currentSegment.byteSize().coerceAtLeast(64L * 1024L) * 2L
        val newCapacity = maxOf(minimumCapacity, minOf(MAX_RETAINED_BYTES, doubledCapacity))
        val newArena = Arena.ofAuto()
        val newSegment = newArena.allocateUninitialized(newCapacity)

        if (offset > 0L) {
            MemorySegment.copy(currentSegment, 0L, newSegment, 0L, offset)
        }

        currentArena = newArena
        currentSegment = newSegment

        return currentSegment.asSlice(offset, requiredBytes)
    }

    companion object {

        private const val MAX_RETAINED_BYTES: Long = 64L * 1024L * 1024L
        private const val MAX_POOLED_BUFFERS: Int = 16

        private val serializationBuffers: ThreadLocal<OffHeapScratchBuffer> = ThreadLocal.withInitial {
            OffHeapScratchBuffer(4L * 1024L * 1024L)
        }

        private val compressionBuffers: ThreadLocal<OffHeapScratchBuffer> = ThreadLocal.withInitial {
            OffHeapScratchBuffer(16L * 1024L * 1024L)
        }

        private val compressionBufferPool: ConcurrentLinkedQueue<OffHeapScratchBuffer> = ConcurrentLinkedQueue()
        private val pooledBufferCount: AtomicInteger = AtomicInteger(0)

        fun getSerializationBuffer(): OffHeapScratchBuffer = serializationBuffers.get()

        fun getCompressionBuffer(): OffHeapScratchBuffer = compressionBuffers.get()

        fun borrowCompressionBuffer(): OffHeapScratchBuffer {
            val recycled = compressionBufferPool.poll()
            if (recycled != null) {
                pooledBufferCount.decrementAndGet()
                return recycled
            }

            return OffHeapScratchBuffer(16L * 1024L * 1024L)
        }

        fun releaseCompressionBuffer(buffer: OffHeapScratchBuffer) {
            if (buffer.currentSegment.byteSize() > MAX_RETAINED_BYTES) {
                return
            }

            while (true) {
                val current = pooledBufferCount.get()
                if (current >= MAX_POOLED_BUFFERS) {
                    return
                }
                if (pooledBufferCount.compareAndSet(current, current + 1)) {
                    compressionBufferPool.offer(buffer)
                    return
                }
            }
        }

    }

}

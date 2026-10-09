package org.punkrecordz.totem.io.memory

import org.punkrecordz.totem.io.allocateUninitialized
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * Thread-local reusable off-heap scratch buffer for zero-allocation serialization and compression pipelines.
 *
 * Strategy:
 * Avoids OS virtual memory page allocation and Windows page fault overhead (which costs 15-30ms for 45MB+ buffers)
 * by retaining pre-committed off-heap [MemorySegment] instances per thread. Slices of the requested size are provided
 * without re-allocation when capacity suffices. Expands geometrically when larger sizes are requested.
 * By allocating via [Arena.ofAuto], scratch buffers can be safely accessed concurrently by worker threads in [TotemThreadPool].
 *
 * Trade-offs:
 * Holds off-heap memory tied to active worker threads. Using [Arena.ofAuto] ensures underlying memory is
 * released when the thread or buffer becomes unreachable.
 *
 * Performance targets:
 * Sub-microsecond scratch segment acquisition with zero OS page faults during repeated save operations.
 */
class OffHeapScratchBuffer(initialCapacity: Long = 64L * 1024L * 1024L) {

    private var currentArena: Arena = Arena.ofAuto()
    private var currentSegment: MemorySegment = currentArena.allocateUninitialized(initialCapacity)

    fun acquire(requiredBytes: Long): MemorySegment {
        if (currentSegment.byteSize() < requiredBytes) {
            val newCapacity = maxOf(requiredBytes, currentSegment.byteSize() * 2L)
            currentArena = Arena.ofAuto()
            currentSegment = currentArena.allocateUninitialized(newCapacity)
        }

        return currentSegment.asSlice(0L, requiredBytes)
    }

    companion object {

        private val serializationBuffers: ThreadLocal<OffHeapScratchBuffer> = ThreadLocal.withInitial {
            OffHeapScratchBuffer()
        }

        private val compressionBuffers: ThreadLocal<OffHeapScratchBuffer> = ThreadLocal.withInitial {
            OffHeapScratchBuffer()
        }

        fun getSerializationBuffer(): OffHeapScratchBuffer = serializationBuffers.get()

        fun getCompressionBuffer(): OffHeapScratchBuffer = compressionBuffers.get()

    }

}

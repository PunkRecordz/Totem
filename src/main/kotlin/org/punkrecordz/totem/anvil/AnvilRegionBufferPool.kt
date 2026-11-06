package org.punkrecordz.totem.anvil

import org.punkrecordz.totem.io.allocateUninitialized
import java.lang.foreign.Arena

object AnvilRegionBufferPool {

    const val DEFAULT_BUFFER_BYTES: Long = 8L * 1024L * 1024L

    private const val SIZE_CLASS_COUNT: Int = 4

    val maxPooledBuffers: Int = minOf(16, maxOf(4, Runtime.getRuntime().availableProcessors()))

    // power-of-two size classes (8..64 MiB) recycle region and batch windows while bounding retained native memory
    private val sizeClasses: Array<RegionBufferSizeClass> = Array(SIZE_CLASS_COUNT) { classIndex ->
        RegionBufferSizeClass(
            capacityBytes = DEFAULT_BUFFER_BYTES shl classIndex,
            maxRetained = maxOf(1, maxPooledBuffers shr classIndex),
        )
    }

    internal fun acquire(requiredBytes: Long = DEFAULT_BUFFER_BYTES): PooledRegionBuffer {
        val sizeClass = sizeClassFor(requiredBytes)
            ?: return PooledRegionBuffer.owned(Arena.ofShared(), requiredBytes)

        sizeClass.poll()?.let { recycledBuffer -> return recycledBuffer }

        val allocatedSegment = Arena.ofAuto().allocateUninitialized(sizeClass.capacityBytes)
        return PooledRegionBuffer(allocatedSegment, isRecyclable = true)
    }

    internal fun release(buffer: PooledRegionBuffer) {
        if (!buffer.isRecyclable) {
            buffer.closeOwnedArena()
            return
        }

        sizeClasses.firstOrNull { sizeClass -> sizeClass.capacityBytes == buffer.capacityBytes }?.offer(buffer)
    }

    fun pooledBufferCount(): Int = sizeClasses.sumOf { sizeClass -> sizeClass.retainedCount }

    fun clear() {
        sizeClasses.forEach { sizeClass -> sizeClass.drain() }
    }

    private fun sizeClassFor(requiredBytes: Long): RegionBufferSizeClass? =
        sizeClasses.firstOrNull { sizeClass -> requiredBytes <= sizeClass.capacityBytes }

}

package org.punkrecordz.totem.anvil

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

internal class RegionBufferSizeClass(
    val capacityBytes: Long,
    private val maxRetained: Int,
) {

    private val availableBuffers: ConcurrentLinkedQueue<PooledRegionBuffer> = ConcurrentLinkedQueue()
    private val availableCount: AtomicInteger = AtomicInteger(0)

    val retainedCount: Int
        get() = availableCount.get()

    fun poll(): PooledRegionBuffer? {
        val recycledBuffer = availableBuffers.poll() ?: return null
        availableCount.decrementAndGet()

        return recycledBuffer
    }

    fun offer(buffer: PooledRegionBuffer) {
        while (true) {
            val currentCount = availableCount.get()
            if (currentCount >= maxRetained) {
                return
            }

            if (availableCount.compareAndSet(currentCount, currentCount + 1)) {
                availableBuffers.offer(buffer)
                return
            }
        }
    }

    fun drain() {
        while (poll() != null) {
            // drain until empty while keeping availableCount synchronized with polled elements
        }
    }

}

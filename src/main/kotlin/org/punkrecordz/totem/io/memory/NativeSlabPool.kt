package org.punkrecordz.totem.io.memory

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal object NativeSlabPool {

    private const val MEBIBYTE: Long = 1024L * 1024L
    private const val MIN_POOLED_BYTES: Long = 1L * MEBIBYTE
    private const val MAX_SLAB_CAPACITY_BYTES: Long = 128L * MEBIBYTE
    private const val DEFAULT_MAX_CACHED_BYTES: Long = 256L * MEBIBYTE
    private const val MAX_SLABS_PER_BUCKET: Int = 6

    private val SLAB_CAPACITIES_BYTES: LongArray = longArrayOf(
        1L * MEBIBYTE,
        (3L * MEBIBYTE) / 2L,
        2L * MEBIBYTE,
        3L * MEBIBYTE,
        4L * MEBIBYTE,
        6L * MEBIBYTE,
        8L * MEBIBYTE,
        12L * MEBIBYTE,
        16L * MEBIBYTE,
        24L * MEBIBYTE,
        32L * MEBIBYTE,
        48L * MEBIBYTE,
        64L * MEBIBYTE,
        96L * MEBIBYTE,
        128L * MEBIBYTE,
    )

    private val maxCachedBytes: Long = System.getProperty("totem.slab.pool.bytes")
        ?.toLongOrNull()
        ?.coerceAtLeast(0L)
        ?: DEFAULT_MAX_CACHED_BYTES

    private val nativeLinker: Linker = Linker.nativeLinker()
    private val standardLibrary = nativeLinker.defaultLookup()

    private val mallocHandle: MethodHandle = nativeLinker.downcallHandle(
        standardLibrary.find("malloc").orElseThrow {
            IllegalStateException("Cannot find 'malloc' in native standard library")
        },
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
    )

    private val freeHandle: MethodHandle = nativeLinker.downcallHandle(
        standardLibrary.find("free").orElseThrow {
            IllegalStateException("Cannot find 'free' in native standard library")
        },
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
    )

    // lifo reuse keeps the most recently faulted virtual pages and tlb entries warm
    private val bucketDeques: Array<ConcurrentLinkedDeque<Long>> =
        Array(SLAB_CAPACITIES_BYTES.size) { ConcurrentLinkedDeque() }

    private val bucketCounts: Array<AtomicInteger> =
        Array(SLAB_CAPACITIES_BYTES.size) { AtomicInteger(0) }

    private val totalCachedBytes: AtomicLong = AtomicLong(0L)

    fun allocate(
        byteSize: Long,
        arena: Arena,
    ): MemorySegment {
        require(byteSize >= 0L) {
            "Allocation size must be non-negative: byteSize=$byteSize"
        }

        if (byteSize == 0L) {
            return MemorySegment.NULL
        }

        if (byteSize < MIN_POOLED_BYTES || byteSize > MAX_SLAB_CAPACITY_BYTES || maxCachedBytes < MIN_POOLED_BYTES) {
            val directSegment = invokeMalloc(byteSize)

            return try {
                directSegment.reinterpret(byteSize, arena) { segmentToFree ->
                    invokeFree(segmentToFree)
                }
            } catch (throwable: Throwable) {
                invokeFree(directSegment)
                throw throwable
            }
        }

        val bucketIndex = findBucketIndex(byteSize)
        val slabCapacity = SLAB_CAPACITIES_BYTES[bucketIndex]
        val cachedAddress = bucketDeques[bucketIndex].pollFirst()

        val rawAddress = if (cachedAddress != null) {
            bucketCounts[bucketIndex].decrementAndGet()
            totalCachedBytes.addAndGet(-slabCapacity)
            MemorySegment.ofAddress(cachedAddress)
        } else {
            invokeMalloc(slabCapacity)
        }

        return try {
            rawAddress.reinterpret(byteSize, arena) { releasedSegment ->
                releaseSlab(releasedSegment, bucketIndex, slabCapacity)
            }
        } catch (throwable: Throwable) {
            releaseSlab(rawAddress, bucketIndex, slabCapacity)
            throw throwable
        }
    }

    private fun findBucketIndex(byteSize: Long): Int {
        for (index in SLAB_CAPACITIES_BYTES.indices) {
            if (SLAB_CAPACITIES_BYTES[index] >= byteSize) {
                return index
            }
        }

        return SLAB_CAPACITIES_BYTES.lastIndex
    }

    private fun releaseSlab(
        segment: MemorySegment,
        bucketIndex: Int,
        slabCapacity: Long,
    ) {
        if (tryReserveCacheBudget(bucketIndex, slabCapacity)) {
            bucketDeques[bucketIndex].offerFirst(segment.address())
            return
        }

        invokeFree(segment)
    }

    private fun tryReserveCacheBudget(
        bucketIndex: Int,
        slabCapacity: Long,
    ): Boolean {
        val bucketCounter = bucketCounts[bucketIndex]
        while (true) {
            val currentCount = bucketCounter.get()
            if (currentCount >= MAX_SLABS_PER_BUCKET) {
                return false
            }
            if (bucketCounter.compareAndSet(currentCount, currentCount + 1)) {
                break
            }
        }

        while (true) {
            val currentBytes = totalCachedBytes.get()
            val updatedBytes = currentBytes + slabCapacity
            if (updatedBytes > maxCachedBytes) {
                bucketCounter.decrementAndGet()
                return false
            }
            if (totalCachedBytes.compareAndSet(currentBytes, updatedBytes)) {
                return true
            }
        }
    }

    private fun invokeMalloc(byteSize: Long): MemorySegment {
        val allocated = mallocHandle.invokeExact(byteSize) as MemorySegment
        if (allocated == MemorySegment.NULL) {
            throw OutOfMemoryError("Native malloc failed to allocate $byteSize bytes")
        }

        return allocated
    }

    private fun invokeFree(segment: MemorySegment) {
        freeHandle.invokeExact(segment)
    }

}

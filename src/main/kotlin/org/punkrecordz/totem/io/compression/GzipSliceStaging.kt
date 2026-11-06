package org.punkrecordz.totem.io.compression

import org.punkrecordz.totem.io.allocateUninitialized
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.Reference

internal object GzipSliceStaging {

    inline fun <R> withStagedDescriptors(
        slices: List<MemorySegment>,
        scratch: CompressionScratch,
        action: (descriptors: MemorySegment, descriptorCount: Int) -> R,
    ): R {
        var nonEmptyCount = 0
        var totalHeapBytes = 0L

        for (slice in slices) {
            val sliceLength = slice.byteSize()
            if (sliceLength <= 0L) {
                continue
            }
            nonEmptyCount++
            if (!slice.isNative || slice.address() == 0L) {
                totalHeapBytes = Math.addExact(totalHeapBytes, sliceLength)
            }
        }

        val descriptorsSegment = scratch.acquireDescriptorBuffer(nonEmptyCount)
        if (nonEmptyCount == 0) {
            return action(descriptorsSegment, 0)
        }

        return when {
            totalHeapBytes == 0L -> populateAndInvoke(
                slices = slices,
                descriptorsSegment = descriptorsSegment,
                heapStaging = MemorySegment.NULL,
                action = action,
            )
            totalHeapBytes <= CompressionScratch.MAX_OUTPUT_CAPACITY_BYTES -> populateAndInvoke(
                slices = slices,
                descriptorsSegment = descriptorsSegment,
                heapStaging = scratch.stageHeapInput(totalHeapBytes),
                action = action,
            )
            else -> Arena.ofConfined().use { stagingArena ->
                val confinedStaging = stagingArena.allocateUninitialized(totalHeapBytes)
                populateAndInvoke(
                    slices = slices,
                    descriptorsSegment = descriptorsSegment,
                    heapStaging = confinedStaging,
                    action = action,
                )
            }
        }
    }

    @PublishedApi
    internal inline fun <R> populateAndInvoke(
        slices: List<MemorySegment>,
        descriptorsSegment: MemorySegment,
        heapStaging: MemorySegment,
        action: (descriptors: MemorySegment, descriptorCount: Int) -> R,
    ): R {
        var currentHeapOffset = 0L
        var descriptorCount = 0

        for (slice in slices) {
            val sliceLength = slice.byteSize()
            if (sliceLength <= 0L) {
                continue
            }

            val nativeAddress = when {
                slice.isNative && slice.address() != 0L -> slice.address()
                else -> {
                    MemorySegment.copy(slice, 0L, heapStaging, currentHeapOffset, sliceLength)
                    val stagedAddress = heapStaging.address() + currentHeapOffset
                    currentHeapOffset += sliceLength
                    stagedAddress
                }
            }

            val descriptorOffset = descriptorCount.toLong() * CompressionScratch.DESCRIPTOR_STRIDE_BYTES
            descriptorsSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, descriptorOffset, nativeAddress)
            descriptorsSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, descriptorOffset + 8L, sliceLength)
            descriptorCount++
        }

        return try {
            action(descriptorsSegment, descriptorCount)
        } finally {
            Reference.reachabilityFence(slices)
            Reference.reachabilityFence(descriptorsSegment)
            Reference.reachabilityFence(heapStaging)
        }
    }

}

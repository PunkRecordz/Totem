package org.punkrecordz.totem

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.OptionsBuilder
import org.punkrecordz.totem.tag.ByteArrayTag
import org.punkrecordz.totem.impl.native.NativeShortView
import org.punkrecordz.totem.impl.native.NativeByteArrayTag
import org.punkrecordz.totem.ffi.TotemSys
import org.punkrecordz.totem.io.MemoryLayouts
import org.punkrecordz.totem.view.toVarIntByteArray
import org.punkrecordz.totem.view.toVarIntShortArray
import org.punkrecordz.totem.io.allocateUninitialized
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.Random
import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class VarIntBenchmarks {

    private lateinit var arena: Arena
    private lateinit var heapShortArray: ShortArray
    private lateinit var heapVarIntBytes: ByteArray
    private var offHeapShortView = NativeShortView(MemorySegment.NULL)
    private lateinit var offHeapVarIntTag: ByteArrayTag

    private lateinit var destinationHeapShortArray: ShortArray
    private lateinit var destinationHeapVarIntBytes: ByteArray
    private var destinationOffHeapShortView = NativeShortView(MemorySegment.NULL)
    private lateinit var destinationOffHeapVarIntSegment: MemorySegment

    companion object {

        private const val VOLUME = 500_000_000

        @JvmStatic
        fun main(args: Array<String>) {
            val options = OptionsBuilder()
                .include(VarIntBenchmarks::class.java.simpleName)
                .build()
            Runner(options).run()
        }

    }

    @Setup(Level.Trial)
    fun setup() {
        arena = Arena.ofShared()
        val random = Random(42)

        heapShortArray = ShortArray(VOLUME) { index ->
            when (random.nextInt(3)) {
                0 -> 0.toShort()
                1 -> (random.nextInt(128)).toShort()
                else -> (random.nextInt(500)).toShort()
            }
        }

        heapVarIntBytes = heapShortArray.toVarIntBytes()

        offHeapShortView = NativeShortView.of(
            heapShortArray,
            arena,
        )
        offHeapVarIntTag = offHeapShortView.toVarIntByteArray(arena)

        destinationHeapShortArray = ShortArray(VOLUME)
        destinationHeapVarIntBytes = ByteArray(VOLUME * 3 + 12)
        destinationOffHeapShortView = NativeShortView.of(VOLUME, arena)
        destinationOffHeapVarIntSegment = arena.allocateUninitialized(VOLUME * 3L + 12L)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        arena.close()
    }

    @Benchmark
    fun benchmarkHeapDecode(blackhole: Blackhole) {
        heapVarIntBytes.toVarInt(VOLUME, destinationHeapShortArray)
        blackhole.consume(destinationHeapShortArray)
    }

    @Benchmark
    fun benchmarkOffHeapDecode(blackhole: Blackhole) {
        val sourceSlice = (offHeapVarIntTag as NativeByteArrayTag).segment.asSlice(4L)
        val destinationSlice = destinationOffHeapShortView.segment.asSlice(4L)

        TotemSys.decodeVarIntShorts(
            sourceSlice,
            destinationSlice,
            VOLUME,
        )
        blackhole.consume(destinationOffHeapShortView)
    }

    @Benchmark
    fun benchmarkHeapEncode(blackhole: Blackhole) {
        heapShortArray.toVarIntBytes(destinationHeapVarIntBytes)
        blackhole.consume(destinationHeapVarIntBytes)
    }

    @Benchmark
    fun benchmarkOffHeapEncode(blackhole: Blackhole) {
        val sourceSlice = offHeapShortView.segment.asSlice(4L)
        val destinationSlice = destinationOffHeapVarIntSegment.asSlice(4L)

        val payloadSize = TotemSys.encodeShortsVarInt(
            sourceSlice,
            offHeapShortView.size.toLong(),
            destinationSlice,
        )

        destinationOffHeapVarIntSegment.set(MemoryLayouts.INT, 0L, payloadSize.toInt())
        blackhole.consume(destinationOffHeapVarIntSegment)
    }

    private fun ByteArray.toVarInt(volume: Int, destination: ShortArray) {
        var sourceIndex = 0
        var destinationIndex = 0

        while (sourceIndex < this.size && destinationIndex < volume) {
            var value = 0
            var shift = 0
            var byteValue: Int

            do {
                byteValue = this[sourceIndex++].toInt()
                value = value or ((byteValue and 0x7F) shl shift)
                shift += 7
            } while (byteValue and 0x80 != 0)

            destination[destinationIndex++] = value.toShort()
        }
    }

    private fun ShortArray.toVarIntBytes(destination: ByteArray): Int {
        var byteIndex = 0

        for (value in this) {
            var elementValue = value.toInt() and 0xFFFF

            do {
                var temporaryValue = elementValue and 0x7F
                elementValue = elementValue ushr 7

                if (elementValue != 0) {
                    temporaryValue = temporaryValue or 0x80
                }

                destination[byteIndex++] = temporaryValue.toByte()
            } while (elementValue != 0)
        }

        return byteIndex
    }

    private fun ShortArray.toVarIntBytes(): ByteArray {
        var size = 0

        for (value in this) {
            var elementValue = value.toInt() and 0xFFFF

            do {
                size++
                elementValue = elementValue ushr 7
            } while (elementValue != 0)
        }

        val bytes = ByteArray(size)
        var byteIndex = 0

        for (value in this) {
            var elementValue = value.toInt() and 0xFFFF

            do {
                var temporaryValue = elementValue and 0x7F
                elementValue = elementValue ushr 7

                if (elementValue != 0) {
                    temporaryValue = temporaryValue or 0x80
                }

                bytes[byteIndex++] = temporaryValue.toByte()
            } while (elementValue != 0)
        }

        return bytes
    }

}

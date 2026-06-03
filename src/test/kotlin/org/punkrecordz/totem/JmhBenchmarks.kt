package org.punkrecordz.totem

import net.querz.nbt.io.NBTUtil
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.OptionsBuilder
import org.punkrecordz.totem.io.compression.CompressionType
import org.punkrecordz.totem.tag.Tags
import org.punkrecordz.totem.view.toVarIntByteArray
import org.punkrecordz.totem.view.toVarIntShortArray
import java.lang.foreign.Arena
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import net.querz.nbt.tag.CompoundTag as QuerzCompoundTag

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class JmhBenchmarks {

    private val schematicPath = Path("src/test/resources/small-schem-to-test.schem")
    private val tempTotemPath = Path("temp_totem_benchmark.schem")
    private val tempQuerzPath = Path("temp_querz_benchmark.schem")

    @TearDown(Level.Trial)
    fun cleanup() {
        tempTotemPath.deleteIfExists()
        tempQuerzPath.deleteIfExists()
    }

    @Benchmark
    fun benchmarkTotemPipeline(): Long {
        Arena.ofConfined().use { arena ->
            val (name, root) = Totem.load(schematicPath, arena)
            val schematic = root.getCompound("Schematic")!!
            val width = schematic.getShort("Width")!!.toInt()
            val height = schematic.getShort("Height")!!.toInt()
            val length = schematic.getShort("Length")!!.toInt()
            val volume = width * height * length
            val blocksCompound = schematic.getCompound("Blocks")!!

            val blocks = blocksCompound.getByteArray("Data")?.toVarIntShortArray(volume, arena)!!
            for (index in 0 until blocks.size) {
                if (blocks[index] == 0.toShort()) {
                    blocks[index] = 1.toShort()
                }
            }

            val modifiedData = blocks.toVarIntByteArray(arena)

            // Rebuild NBT hierarchy because native tags are immutable off-heap views
            val newBlocksCompound = Tags.compound().apply {
                for ((key, value) in blocksCompound) {
                    if (key == "Data") {
                        put(key, modifiedData)
                    } else {
                        put(key, value)
                    }
                }
            }

            val newSchematic = Tags.compound().apply {
                for ((key, value) in schematic) {
                    if (key == "Blocks") {
                        put(key, newBlocksCompound)
                    } else {
                        put(key, value)
                    }
                }
            }

            val newRoot = Tags.compound().apply {
                for ((key, value) in root) {
                    if (key == "Schematic") {
                        put(key, newSchematic)
                    } else {
                        put(key, value)
                    }
                }
            }

            Totem.save(name, newRoot, tempTotemPath, CompressionType.GZIP)
            return tempTotemPath.toFile().length()
        }
    }

    @Benchmark
    fun benchmarkQuerzPipeline(): Long {
        val namedTag = NBTUtil.read(schematicPath.toFile())
        val root = namedTag.tag as QuerzCompoundTag
        val schematic = root.getCompoundTag("Schematic")
        val width = schematic.getShort("Width").toInt()
        val height = schematic.getShort("Height").toInt()
        val length = schematic.getShort("Length").toInt()
        val volume = width * height * length
        val blocksCompound = schematic.getCompoundTag("Blocks")

        val dataBytes = blocksCompound.getByteArray("Data")
        val blocks = dataBytes.toVarInt(volume)

        for (i in blocks.indices) {
            if (blocks[i] == 0.toShort()) {
                blocks[i] = 1.toShort()
            }
        }

        val modifiedBytes = blocks.toVarIntBytes()
        blocksCompound.putByteArray("Data", modifiedBytes)

        NBTUtil.write(namedTag, tempQuerzPath.toFile())
        return tempQuerzPath.toFile().length()
    }

    private fun ByteArray.toVarInt(volume: Int): ShortArray {
        val destination = ShortArray(volume)
        var srcIndex = 0
        var dstIndex = 0
        while (srcIndex < this.size && dstIndex < volume) {
            var value = 0
            var shift = 0
            var b: Int
            do {
                b = this[srcIndex++].toInt()
                value = value or ((b and 0x7F) shl shift)
                shift += 7
            } while (b and 0x80 != 0)
            destination[dstIndex++] = value.toShort()
        }
        return destination
    }

    private fun ShortArray.toVarIntBytes(): ByteArray {
        var size = 0
        for (value in this) {
            var v = value.toInt() and 0xFFFF
            do {
                size++
                v = v ushr 7
            } while (v != 0)
        }
        val bytes = ByteArray(size)
        var byteIndex = 0
        for (value in this) {
            var v = value.toInt() and 0xFFFF
            do {
                var temp = v and 0x7F
                v = v ushr 7
                if (v != 0) {
                    temp = temp or 0x80
                }
                bytes[byteIndex++] = temp.toByte()
            } while (v != 0)
        }
        return bytes
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val options = OptionsBuilder()
                .include(JmhBenchmarks::class.java.simpleName)
                .build()
            Runner(options).run()
        }
    }
}

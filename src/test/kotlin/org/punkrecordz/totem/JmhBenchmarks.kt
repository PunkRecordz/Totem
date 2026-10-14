package org.punkrecordz.totem

import net.querz.nbt.io.NBTUtil
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.OptionsBuilder
import org.punkrecordz.totem.io.compression.CompressionType
import org.punkrecordz.totem.tag.ByteArrayTag
import org.punkrecordz.totem.tag.CompoundTag
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

    private val schematicPath = Path("src/test/resources/18578.schem")
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

            // support both sponge schematic v2 and v3 formats
            val schematic = if (root.containsKey("Width")) root else root.getCompound("Schematic")!!
            val width = schematic.getShort("Width")!!.toInt()
            val height = schematic.getShort("Height")!!.toInt()
            val length = schematic.getShort("Length")!!.toInt()
            val volume = width * height * length

            val dataTag = if (schematic.containsKey("BlockData")) {
                schematic.getByteArray("BlockData")!!
            } else {
                schematic.getCompound("Blocks")!!.getByteArray("Data")!!
            }

            val blocks = dataTag.toVarIntShortArray(volume, arena)
            blocks.replaceAll(0.toShort(), 1.toShort())

            val modifiedData = blocks.toVarIntByteArray(arena)
            val newRoot = rebuildRootWithBlockData(root, schematic, modifiedData)

            Totem.save(name, newRoot, tempTotemPath, CompressionType.GZIP)
            return tempTotemPath.toFile().length()
        }
    }

    @Benchmark
    fun benchmarkTotemPipelineScalar(): Long {
        Arena.ofConfined().use { arena ->
            val (name, root) = Totem.load(schematicPath, arena)

            // support both sponge schematic v2 and v3 formats
            val schematic = if (root.containsKey("Width")) root else root.getCompound("Schematic")!!
            val width = schematic.getShort("Width")!!.toInt()
            val height = schematic.getShort("Height")!!.toInt()
            val length = schematic.getShort("Length")!!.toInt()
            val volume = width * height * length

            val dataTag = if (schematic.containsKey("BlockData")) {
                schematic.getByteArray("BlockData")!!
            } else {
                schematic.getCompound("Blocks")!!.getByteArray("Data")!!
            }

            val blocks = dataTag.toVarIntShortArray(volume, arena)
            for (index in 0 until blocks.size) {
                if (blocks[index] == 0.toShort()) {
                    blocks[index] = 1.toShort()
                }
            }

            val modifiedData = blocks.toVarIntByteArray(arena)
            val newRoot = rebuildRootWithBlockData(root, schematic, modifiedData)

            Totem.save(name, newRoot, tempTotemPath, CompressionType.GZIP)
            return tempTotemPath.toFile().length()
        }
    }

    private fun rebuildRootWithBlockData(
        root: CompoundTag,
        schematic: CompoundTag,
        modifiedData: ByteArrayTag,
    ): CompoundTag {
        if (root.containsKey("Width")) {
            return Tags.compound().apply {
                for ((key, value) in root) {
                    if (key == "BlockData") {
                        put(key, modifiedData)
                    } else {
                        put(key, value)
                    }
                }
            }
        }

        val blocksCompound = schematic.getCompound("Blocks")!!
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

        return Tags.compound().apply {
            for ((key, value) in root) {
                if (key == "Schematic") {
                    put(key, newSchematic)
                } else {
                    put(key, value)
                }
            }
        }
    }

    @Benchmark
    fun benchmarkQuerzPipeline(): Long {
        val namedTag = NBTUtil.read(schematicPath.toFile())
        val root = namedTag.tag as QuerzCompoundTag

        // support both sponge schematic v2 and v3 formats
        val schematic = if (root.containsKey("Width")) root else root.getCompoundTag("Schematic")
        val width = schematic.getShort("Width").toInt()
        val height = schematic.getShort("Height").toInt()
        val length = schematic.getShort("Length").toInt()
        val volume = width * height * length

        val dataBytes = if (schematic.containsKey("BlockData")) {
            schematic.getByteArray("BlockData")
        } else {
            schematic.getCompoundTag("Blocks").getByteArray("Data")
        }

        val blocks = dataBytes.toVarInt(volume)
        for (index in blocks.indices) {
            if (blocks[index] == 0.toShort()) {
                blocks[index] = 1.toShort()
            }
        }

        val modifiedBytes = blocks.toVarIntBytes()
        if (schematic.containsKey("BlockData")) {
            schematic.putByteArray("BlockData", modifiedBytes)
        } else {
            schematic.getCompoundTag("Blocks").putByteArray("Data", modifiedBytes)
        }

        NBTUtil.write(namedTag, tempQuerzPath.toFile())
        return tempQuerzPath.toFile().length()
    }

    private fun ByteArray.toVarInt(volume: Int): ShortArray {
        val destination = ShortArray(volume)
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
        return destination
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

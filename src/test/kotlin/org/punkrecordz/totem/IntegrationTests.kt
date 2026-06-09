package org.punkrecordz.totem

import net.querz.nbt.io.NBTUtil
import org.junit.jupiter.api.Test
import org.punkrecordz.totem.io.compression.CompressionType
import org.punkrecordz.totem.tag.Tags
import org.punkrecordz.totem.view.toVarIntByteArray
import org.punkrecordz.totem.view.toVarIntShortArray
import java.lang.foreign.Arena
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.querz.nbt.tag.CompoundTag as QuerzCompoundTag

class IntegrationTests {

    private val schematicPath = Path("src/test/resources/18578.schem")

    @Test
    fun testSmallSchematicPipeline() {
        val tempOutputFile = Path("temp_pipeline_output.schem")
        tempOutputFile.deleteIfExists()

        try {
            Arena.ofConfined().use { arena ->
                // 1. Load schematic
                val (name, root) = Totem.load(schematicPath, arena)

                // Support both nested "Schematic" or direct properties for resilience
                val schematic = if (root.containsKey("Width")) root else root.getCompound("Schematic")!!

                val width = schematic.getShort("Width")!!.toInt()
                val height = schematic.getShort("Height")!!.toInt()
                val length = schematic.getShort("Length")!!.toInt()
                val volume = width * height * length

                // Support both V2 and V3 formats
                val isV3 = schematic.containsKey("BlockData")
                val dataTag = if (isV3) {
                    schematic.getByteArray("BlockData")!!
                } else {
                    schematic.getCompound("Blocks")!!.getByteArray("Data")!!
                }

                val dataView = dataTag.toVarIntShortArray(volume, arena)
                val originalSize = dataView.size
                for (index in 0 until dataView.size) {
                    if (dataView[index] == 0.toShort()) {
                        dataView[index] = 1.toShort()
                    }
                }

                // 3. Re-encode block data and rebuild NBT hierarchy since native views are immutable
                val modifiedData = dataView.toVarIntByteArray(arena)
                val newRoot = if (root.containsKey("Width")) {
                    Tags.compound().apply {
                        for ((key, value) in root) {
                            if (key == "BlockData") {
                                put(key, modifiedData)
                            } else {
                                put(key, value)
                            }
                        }
                    }
                } else {
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

                    Tags.compound().apply {
                        for ((key, value) in root) {
                            if (key == "Schematic") {
                                put(key, newSchematic)
                            } else {
                                put(key, value)
                            }
                        }
                    }
                }

                // 4. Save to temporary output
                Totem.save(name, newRoot, tempOutputFile, CompressionType.GZIP)

                // 5. Verify the saved file exists
                assertTrue(tempOutputFile.exists())

                // --- Round-trip Validation (Totem reads Totem's output) ---
                val (_, reloadedRoot) = Totem.load(tempOutputFile, arena)
                val reloadedSchematic = if (reloadedRoot.containsKey("Width")) reloadedRoot else reloadedRoot.getCompound("Schematic")!!
                
                val reloadedDataTag = if (isV3) {
                    reloadedSchematic.getByteArray("BlockData")!!
                } else {
                    reloadedSchematic.getCompound("Blocks")!!.getByteArray("Data")!!
                }
                
                val reloadedData = reloadedDataTag.toVarIntShortArray(volume, arena)

                assertEquals(originalSize, reloadedData.size)

                // --- Cross-Validation (Querz reads Totem's output) ---
                val querzNamedTag = NBTUtil.read(tempOutputFile.toFile())
                val querzRoot = querzNamedTag.tag as QuerzCompoundTag
                val querzSchem = if (querzRoot.containsKey("Width")) querzRoot else querzRoot.getCompoundTag("Schematic")!!
                
                val querzDataBytes = if (isV3) {
                    querzSchem.getByteArray("BlockData")
                } else {
                    querzSchem.getCompoundTag("Blocks").getByteArray("Data")
                }

                val decodedQuerz = querzDataBytes.toVarInt(volume)
                assertEquals(originalSize, decodedQuerz.size)

                // Verify that all 0 (air) block states were indeed modified to 1 (stone)
                for (i in decodedQuerz.indices) {
                    assertTrue(
                        decodedQuerz[i] != 0.toShort(),
                        "Cross-validation failed: Found unmodified block state 0 at index $i via Querz NBT."
                    )
                }
            }
        } finally {
            // Strict cleanup of generated files
            tempOutputFile.deleteIfExists()
        }
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

}

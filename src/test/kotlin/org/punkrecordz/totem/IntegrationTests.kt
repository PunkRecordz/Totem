package org.punkrecordz.totem

import net.querz.nbt.io.NBTUtil
import org.junit.jupiter.api.Test
import org.punkrecordz.totem.io.compression.CompressionType
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

    private val schematicPath = Path("src/test/resources/a.schematic")

    @Test
    fun testSmallSchematicPipeline() {
        val tempOutputFile = Path("temp_pipeline_output.schem")
        tempOutputFile.deleteIfExists()

        try {
            Arena.ofConfined().use { arena ->
                // 1. Load small schematic
                val (name, root) = Totem.load(schematicPath, arena)

                // Support both nested "Schematic" or direct properties for resilience
                val schematic = root.getCompound("Schematic") ?: root

                val width = (schematic.getShort("Width") ?: 1.toShort()).toInt()
                val height = (schematic.getShort("Height") ?: 1.toShort()).toInt()
                val length = (schematic.getShort("Length") ?: 1.toShort()).toInt()
                val volume = width * height * length

                val blocksCompound = schematic.getCompound("Blocks")
                if (blocksCompound != null) {
                    val dataView = blocksCompound.getByteArray("Data")?.toVarIntShortArray(volume, arena)
                    if (dataView != null) {
                        // 2. Perform zero-allocation modification (replace air 0 with stone 1)
                        val originalSize = dataView.size
                        dataView.replace(0.toShort(), 1.toShort())

                        // 3. Re-encode block data
                        val modifiedData = dataView.toVarIntByteArray(arena)
                        blocksCompound["Data"] = modifiedData

                        // 4. Save to temporary output
                        Totem.save(name, root, tempOutputFile, CompressionType.GZIP)

                        // 5. Verify the saved file exists
                        assertTrue(tempOutputFile.exists())

                        // --- Round-trip Validation (Totem reads Totem's output) ---
                        val (_, reloadedRoot) = Totem.load(tempOutputFile, arena)
                        val reloadedSchematic = reloadedRoot.getCompound("Schematic") ?: reloadedRoot
                        val reloadedBlocks = reloadedSchematic.getCompound("Blocks")!!
                        val reloadedData = reloadedBlocks.getByteArray("Data")?.toVarIntShortArray(volume, arena)!!

                        assertEquals(originalSize, reloadedData.size)

                        // --- Cross-Validation (Querz reads Totem's output) ---
                        val querzNamedTag = NBTUtil.read(tempOutputFile.toFile())
                        val querzRoot = querzNamedTag.tag as QuerzCompoundTag
                        val querzSchem = querzRoot.getCompoundTag("Schematic") ?: querzRoot
                        val querzBlocks = querzSchem.getCompoundTag("Blocks")!!
                        val querzDataBytes = querzBlocks.getByteArray("Data")

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

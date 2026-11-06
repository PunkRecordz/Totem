package org.punkrecordz.totem.bench

import org.punkrecordz.totem.codec.BlockDataCodec
import org.punkrecordz.totem.tag.CompoundTag
import org.punkrecordz.totem.tag.TagType
import org.punkrecordz.totem.tag.Tags
import java.util.Random

// entropy tuned to land near vanilla 1.21 ratios (~100 KiB raw for ~8-12 KiB zlib)
internal object RealisticChunkFactory {

    private val blockNames: List<String> = listOf(
        "minecraft:stone", "minecraft:deepslate", "minecraft:dirt", "minecraft:grass_block",
        "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff",
        "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:copper_ore", "minecraft:gravel",
        "minecraft:water", "minecraft:lava", "minecraft:cave_air", "minecraft:air",
    )

    fun build(
        chunkX: Int,
        chunkZ: Int,
        seed: Long,
    ): CompoundTag = Tags.compound {
        val random = Random(seed)

        putInt("DataVersion", 4189)
        putInt("xPos", chunkX)
        putInt("yPos", -4)
        putInt("zPos", chunkZ)
        putString("Status", "minecraft:full")
        putLong("LastUpdate", random.nextLong(1_000_000L))
        putLong("InhabitedTime", random.nextLong(100_000L))

        put("Heightmaps", Tags.compound {
            for (heightmapName in listOf("MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES", "OCEAN_FLOOR", "WORLD_SURFACE")) {
                val heights = ShortArray(256) { (120 + random.nextInt(24)).toShort() }
                put(heightmapName, Tags.longArray(BlockDataCodec.packShorts(heights, bitsPerEntry = 9)))
            }
        })

        put("sections", Tags.list<CompoundTag>(TagType.COMPOUND) {
            for (sectionIndex in 0 until 24) {
                add(buildSection(sectionIndex, random))
            }
        })

        put("block_entities", Tags.list<CompoundTag>(TagType.COMPOUND) {
            repeat(random.nextInt(6)) { entityIndex ->
                add(Tags.compound {
                    putString("id", "minecraft:chest")
                    putInt("x", chunkX * 16 + entityIndex)
                    putInt("y", 40 + random.nextInt(60))
                    putInt("z", chunkZ * 16 + entityIndex)
                    putString("CustomName", "Loot-${random.nextInt(10_000)}")
                })
            }
        })
    }

    fun buildModernTestChunk(
        chunkX: Int = 0,
        chunkZ: Int = 0,
        seed: Long = 12345L,
    ): CompoundTag = Tags.compound {
        val random = Random(seed)
        putInt("DataVersion", 4189)
        putInt("xPos", chunkX)
        putInt("yPos", -4)
        putInt("zPos", chunkZ)
        putString("Status", "minecraft:full")

        val sections = Tags.list<CompoundTag>(TagType.COMPOUND) {
            for (sectionIndex in 0 until 24) {
                val baseBlockId = when {
                    sectionIndex < 8 -> 2
                    sectionIndex < 14 -> 1
                    else -> 0
                }
                val blockIndices = ShortArray(4096) { blockOffset ->
                    val stateId = if (baseBlockId != 0 && random.nextInt(64) == 0) {
                        3 + (blockOffset and 3)
                    } else {
                        baseBlockId
                    }
                    stateId.toShort()
                }
                val packedBlockStates = BlockDataCodec.packShorts(blockIndices, bitsPerEntry = 4, precise = false)
                val skyLightFill = if (sectionIndex >= 14) 0xFF.toByte() else 0x00.toByte()
                val skyLight = ByteArray(2048) { skyLightFill }
                val blockLight = ByteArray(2048) { byteIndex ->
                    if (byteIndex % 257 == 0) 0x0E.toByte() else 0x00.toByte()
                }

                add(
                    Tags.compound {
                        putByte("Y", (sectionIndex - 4).toByte())
                        put("SkyLight", Tags.byteArray(skyLight))
                        put("BlockLight", Tags.byteArray(blockLight))
                        put("block_states", Tags.compound {
                            put("data", Tags.longArray(packedBlockStates))
                        })
                    },
                )
            }
        }

        put("sections", sections)
    }

    private fun buildSection(
        sectionIndex: Int,
        random: Random,
    ): CompoundTag = Tags.compound {
        val isUnderground = sectionIndex < 16
        val paletteSize = if (isUnderground) 4 + random.nextInt(8) else 1
        val bitsPerEntry = BlockDataCodec.getBitsPerBlock(paletteSize)
        val dominantState = random.nextInt(paletteSize)

        putByte("Y", (sectionIndex - 4).toByte())
        put("block_states", Tags.compound {
            put("palette", Tags.list<CompoundTag>(TagType.COMPOUND) {
                for (paletteIndex in 0 until paletteSize) {
                    add(Tags.compound { putString("Name", blockNames[(paletteIndex + sectionIndex) % blockNames.size]) })
                }
            })

            if (paletteSize > 1) {
                val blockIndices = ShortArray(4096) {
                    val state = if (random.nextInt(48) == 0) random.nextInt(paletteSize) else dominantState
                    state.toShort()
                }
                put("data", Tags.longArray(BlockDataCodec.packShorts(blockIndices, bitsPerEntry)))
            }
        })
        put("biomes", Tags.compound {
            put("palette", Tags.list<CompoundTag>(TagType.COMPOUND) {
                add(Tags.compound { putString("Name", "minecraft:plains") })
            })
        })
        put("SkyLight", Tags.byteArray(ByteArray(2048) { byteIndex ->
            when {
                !isUnderground -> 0xFF.toByte()
                random.nextInt(128) == 0 -> random.nextInt(256).toByte()
                else -> (byteIndex shr 7).toByte()
            }
        }))

        if (isUnderground) {
            put("BlockLight", Tags.byteArray(ByteArray(2048) { if (random.nextInt(256) == 0) random.nextInt(256).toByte() else 0 }))
        }
    }

}

package org.punkrecordz.totem

import org.junit.jupiter.api.Test
import org.punkrecordz.totem.anvil.AnvilRegionFile
import org.punkrecordz.totem.tag.CompoundTag
import org.punkrecordz.totem.tag.Tags
import java.io.File
import java.lang.foreign.Arena
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThreadSafetyTests {

    @Test
    fun testConcurrentWritesAndReads() {
        val temporaryFile = createTempFile("anvil_test_", ".mca").toFile()
        temporaryFile.deleteOnExit()

        val totalThreads = 8
        val chunksPerThread = 10
        val executorService = Executors.newFixedThreadPool(totalThreads)
        val countDownLatch = CountDownLatch(totalThreads)
        val failureCount = AtomicInteger(0)
        val writtenValues = ConcurrentHashMap<Pair<Int, Int>, Int>()

        AnvilRegionFile(
            temporaryFile,
            writeMode = true,
        ).use { regionFile ->
            for (threadIndex in 0 until totalThreads) {
                executorService.submit {
                    try {
                        for (chunkIndex in 0 until chunksPerThread) {
                            val chunkX = threadIndex
                            val chunkZ = chunkIndex
                            val value = threadIndex * 100 + chunkIndex

                            val tag = Tags.compound {
                                putInt("Value", value)
                            }

                            regionFile.writeChunk(
                                chunkX,
                                chunkZ,
                                "Root",
                                tag,
                            )

                            writtenValues[Pair(chunkX, chunkZ)] = value
                        }
                    } catch (exception: Exception) {
                        failureCount.incrementAndGet()
                    } finally {
                        countDownLatch.countDown()
                    }
                }
            }

            assertTrue(
                countDownLatch.await(10, TimeUnit.SECONDS),
                "Timed out waiting for concurrent writes to complete.",
            )

            assertEquals(
                0,
                failureCount.get(),
                "Some threads encountered errors during concurrent writes.",
            )

            Arena.ofConfined().use { targetArena ->
                writtenValues.forEach { (position, expectedValue) ->
                    val (chunkX, chunkZ) = position
                    val result = regionFile.readChunk(
                        chunkX,
                        chunkZ,
                        targetArena,
                    )

                    assertNotNull(result)
                    val root = result.second
                    assertEquals(
                        expectedValue,
                        root.getInt("Value"),
                        "Value mismatch at position ($chunkX, $chunkZ).",
                    )
                }
            }
        }

        executorService.shutdown()
        assertTrue(
            executorService.awaitTermination(5, TimeUnit.SECONDS),
            "Timed out waiting for executor service to terminate.",
        )
    }

    @Test
    fun testClosedRegionFileFailsFast() {
        val temporaryFile = createTempFile("anvil_test_close_", ".mca").toFile()
        temporaryFile.deleteOnExit()

        val regionFile = AnvilRegionFile(
            temporaryFile,
            writeMode = true,
        )

        regionFile.close()

        assertFailsWith<IllegalStateException>(
            message = "Should throw IllegalStateException when listing chunks on a closed file.",
        ) {
            regionFile.listChunks()
        }

        Arena.ofConfined().use { targetArena ->
            assertFailsWith<IllegalStateException>(
                message = "Should throw IllegalStateException when reading on a closed file.",
            ) {
                regionFile.readChunk(
                    0,
                    0,
                    targetArena,
                )
            }
        }

        val tag = Tags.compound {
            putInt("Test", 1)
        }

        assertFailsWith<IllegalStateException>(
            message = "Should throw IllegalStateException when writing on a closed file.",
        ) {
            regionFile.writeChunk(
                0,
                0,
                "Root",
                tag,
            )
        }
    }

}

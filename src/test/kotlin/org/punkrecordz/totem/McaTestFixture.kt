package org.punkrecordz.totem

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

internal class McaFrameSpec(
    val chunkIndex: Int,
    val compressionCode: Int,
    val payload: ByteArray,
    val padSector: Boolean = true,
) {

    constructor(
        chunkX: Int,
        chunkZ: Int,
        compressionCode: Int,
        payload: ByteArray,
        padSector: Boolean = true,
    ) : this(
        chunkIndex = (chunkX and 31) or ((chunkZ and 31) shl 5),
        compressionCode = compressionCode,
        payload = payload,
        padSector = padSector,
    )

}

internal object McaTestFixture {

    fun buildMcaBytes(frames: List<McaFrameSpec>): ByteArray {
        val header = ByteBuffer.allocate(8192).order(ByteOrder.BIG_ENDIAN)
        val body = ByteArrayOutputStream()
        val bodyWriter = DataOutputStream(body)
        var currentSector = 2

        for (frame in frames) {
            val frameBytes = 5 + frame.payload.size
            val sectorCount = (frameBytes + 4095) / 4096
            header.putInt(frame.chunkIndex * 4, (currentSector shl 8) or sectorCount)
            header.putInt(4096 + frame.chunkIndex * 4, 1_700_000_000)

            bodyWriter.writeInt(frame.payload.size + 1)
            bodyWriter.writeByte(frame.compressionCode)
            bodyWriter.write(frame.payload)

            if (frame.padSector) {
                val paddingBytes = sectorCount * 4096 - frameBytes
                if (paddingBytes > 0) {
                    bodyWriter.write(ByteArray(paddingBytes))
                }
            }

            currentSector += sectorCount
        }

        bodyWriter.flush()
        return header.array() + body.toByteArray()
    }

    inline fun withTempDirectory(
        prefix: String = "totem_test_",
        action: (Path) -> Unit,
    ) {
        val directory = createTempDirectory(prefix)
        try {
            action(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

}

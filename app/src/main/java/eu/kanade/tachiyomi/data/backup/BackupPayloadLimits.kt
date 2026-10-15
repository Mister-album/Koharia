package eu.kanade.tachiyomi.data.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Shared by export and import; compressed size alone cannot bound a GZIP payload. */
internal object BackupPayloadLimits {
    const val CONTAINER_BYTES = 32 * 1024 * 1024
    const val PAYLOAD_BYTES = 64 * 1024 * 1024

    fun checkSize(size: Int, limit: Int) {
        if (size > limit) throw BackupSizeLimitExceeded()
    }

    fun read(input: InputStream, limit: Int): ByteArray {
        require(limit >= 0)
        val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, limit))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = limit
        while (true) {
            // Read one byte past the boundary to distinguish an exact fit from excess data.
            val requested = if (remaining >= buffer.size) buffer.size else remaining + 1
            val count = input.read(buffer, 0, requested)
            if (count < 0) return output.toByteArray()
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) return output.toByteArray()
                if (remaining == 0) throw BackupSizeLimitExceeded()
                output.write(byte)
                remaining--
            } else {
                if (count > remaining) throw BackupSizeLimitExceeded()
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }
}

internal class BackupSizeLimitExceeded : IOException("Backup exceeds the supported size limits")

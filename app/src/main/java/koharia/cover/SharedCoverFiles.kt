package koharia.cover

import com.hippo.unifile.UniFile
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** A recoverable replacement within one SAF directory; providers need not support atomic overwrite. */
internal class SharedCoverFiles(
    private val directory: UniFile,
    private val validate: (UniFile) -> Boolean,
) {
    fun find(key: String): UniFile? = directory.findFile("$key.img")
        ?: directory.findFile("$key.previous")

    fun write(key: String, input: InputStream) {
        val previous = directory.findFile("$key.previous")
        val current = directory.findFile("$key.img")
        // Finish recovery before beginning another replacement.
        if (previous != null) {
            if (current == null) {
                if (!previous.renameTo("$key.img")) throw IOException("Cannot recover custom cover")
            } else if (!previous.delete()) {
                throw IOException("Cannot finish custom cover replacement")
            }
        }
        directory.findFile("$key.pending")?.let {
            if (!it.delete()) throw IOException("Cannot remove incomplete custom cover")
        }
        val pending = directory.createFile("$key.pending") ?: throw IOException("Cannot create custom cover")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            pending.openOutputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > MAX_COVER_BYTES) throw IOException("Custom cover exceeds size limit")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
            val writtenDigest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            pending.openInputStream().use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    written += count
                    writtenDigest.update(buffer, 0, count)
                }
            }
            if (size == 0L || written != size || !digest.digest().contentEquals(writtenDigest.digest()) ||
                !validate(pending)
            ) {
                throw IOException("Invalid custom cover")
            }
            val old = directory.findFile("$key.img")
            if (old != null && !old.renameTo("$key.previous")) throw IOException("Cannot preserve custom cover")
            if (!pending.renameTo("$key.img")) {
                old?.renameTo("$key.img")
                throw IOException("Cannot publish custom cover")
            }
            // If cleanup fails, the next operation retries; the published image remains authoritative.
            directory.findFile("$key.previous")?.delete()
        } finally {
            directory.findFile("$key.pending")?.delete()
        }
    }

    fun delete(key: String) {
        // Remove the recovery file first so a failed delete cannot resurrect an older image.
        listOf("previous", "pending", "img").forEach { extension ->
            directory.findFile("$key.$extension")?.let {
                if (!it.delete()) throw IOException("Cannot delete custom cover")
            }
        }
    }

    companion object {
        private const val MAX_COVER_BYTES = 64L * 1024 * 1024

        fun key(sourceId: Long, mangaUrl: String): String = MessageDigest.getInstance("SHA-256")
            .digest("$sourceId\u0000$mangaUrl".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

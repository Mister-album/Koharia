package koharia.core.archive

import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

class ArchiveReader(pfd: ParcelFileDescriptor) : Closeable {
    private val lock = Any()
    private val descriptor = ParcelFileDescriptor.dup(pfd.fileDescriptor)
    private val streams = mutableSetOf<ArchiveInputStream>()
    private var closed = false

    fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T = openStream().use {
        block(generateSequence { it.getNextEntry() })
    }

    /** The prefix supplier is only valid during the predicate; no entry stream escapes enumeration. */
    fun selectEntries(
        checkCancelled: () -> Unit = {},
        predicate: (ArchiveEntry, () -> InputStream) -> Boolean,
    ): List<ArchiveEntry> = openStream(checkCancelled).use { stream ->
        buildList {
            while (true) {
                checkCancelled()
                val entry = stream.getNextEntry() ?: break
                var prefix: ByteArray? = null
                if (predicate(entry) {
                        val bytes = prefix ?: run {
                            val buffer = ByteArray(MAX_PROBE_BYTES)
                            var count = 0
                            while (count < buffer.size) {
                                checkCancelled()
                                val read = stream.read(buffer, count, buffer.size - count)
                                if (read < 0) break
                                count += read
                            }
                            buffer.copyOf(count).also { prefix = it }
                        }
                        ByteArrayInputStream(bytes)
                    }
                ) {
                    add(entry)
                }
            }
        }
    }

    fun getInputStream(entryName: String): InputStream? {
        val archive = openStream()
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.name == entryName) {
                    return archive
                }
            }
        } catch (e: Throwable) {
            archive.close()
            throw e
        }
        archive.close()
        return null
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            streams.toList().also { streams.clear() }
        }
        try {
            var failure: Throwable? = null
            active.forEach {
                try {
                    it.close()
                } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        } finally {
            descriptor.close()
        }
    }

    private fun openStream(checkCancelled: () -> Unit = {}): ArchiveInputStream = synchronized(lock) {
        if (closed) throw IOException("Archive reader is closed")
        val owned = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        try {
            ArchiveInputStream(owned, checkCancelled) { stream ->
                synchronized(lock) { streams.remove(stream) }
            }.also { streams.add(it) }
        } catch (error: Throwable) {
            owned.close()
            throw error
        }
    }

    private companion object {
        const val MAX_PROBE_BYTES = 64 * 1024
    }
}

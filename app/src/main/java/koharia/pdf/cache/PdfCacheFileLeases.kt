package koharia.pdf.cache

import java.io.Closeable
import java.io.File

/** Keeps cached originals available while PDF page loaders own their descriptors. */
internal object PdfCacheFileLeases {
    private val readers = mutableMapOf<String, Int>()

    @Synchronized
    fun acquire(file: File): Closeable {
        val path = file.canonicalPath
        readers[path] = readers.getOrDefault(path, 0) + 1
        var closed = false
        return Closeable {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    val count = checkNotNull(readers[path]) - 1
                    if (count == 0) readers.remove(path) else readers[path] = count
                }
            }
        }
    }

    @Synchronized
    fun deleteIfUnused(file: File): Boolean = file.canonicalPath !in readers && file.delete()
}

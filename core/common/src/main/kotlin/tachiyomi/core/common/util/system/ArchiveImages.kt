package tachiyomi.core.common.util.system

import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import koharia.core.archive.ArchiveEntry
import koharia.core.archive.ArchiveReader
import kotlinx.coroutines.CancellationException
import tachiyomi.decoder.ImageDecoder
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Selects candidates without reopening the archive for every unknown entry. */
fun ArchiveReader.imageEntries(checkCancelled: () -> Unit = {}): List<ArchiveEntry> =
    selectEntries(checkCancelled) { entry, prefix ->
        val name = entry.name.lowercase(Locale.ROOT)
        val basename = name.substringAfterLast('/').substringAfterLast('\\')
        val extension = basename.substringAfterLast('.', "")
        when {
            !entry.isFile || name.startsWith("__macosx/") || basename.startsWith("._") -> false
            ImageUtil.isImage(name) -> true
            extension in archiveSidecarExtensions || basename == "thumbs.db" || basename == ".ds_store" -> false
            else -> ImageUtil.findImageType(prefix) != null
        }
    }

private val archiveSidecarExtensions = setOf("json", "xml", "txt", "nfo", "ini", "url")

/** A broken first page must not force repeated cover failures for the whole book. */
fun ArchiveReader.readCoverImage(checkCancelled: () -> Unit = {}): ByteArray? {
    val candidates = imageEntries(checkCancelled)
        .sortedWith { first, second -> first.name.compareToCaseInsensitiveNaturalOrder(second.name) }
        .take(3)
    for (entry in candidates) {
        checkCancelled()
        val bytes = getInputStream(entry.name)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 32 * 1024 * 1024) return@use null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: continue
        val decodable = try {
            val decoder = ImageDecoder.newInstance(bytes.inputStream()) ?: continue
            try {
                var sample = 1
                while (maxOf(decoder.width, decoder.height) / sample > 1024) sample *= 2
                val bitmap = decoder.decode(sampleSize = sample)
                (bitmap != null).also { bitmap?.recycle() }
            } finally {
                decoder.recycle()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        }
        if (decodable) return bytes
    }
    return null
}

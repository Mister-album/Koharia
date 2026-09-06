package koharia.importing

import android.content.Context
import android.net.Uri
import koharia.connection.ConnectionMediaImportItem
import koharia.media.LocalMediaFormats
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import java.io.File
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object ImageComicArchive {
    suspend fun create(
        context: Context,
        title: String,
        images: List<ConnectionMediaImportItem>,
        onProgress: (Int) -> Unit = {},
    ): File = withIOContext {
        require(images.size >= 2 && images.all { LocalMediaFormats.isImage(it.extension) })
        val name = title.trim().removeSuffix(".cbz").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim(' ', '.').take(120)
        require(name.isNotBlank())
        val root = File(context.cacheDir, "image-comic-import").apply { mkdirs() }
        val directory = Files.createTempDirectory(root.toPath(), "comic-").toFile()
        val output = File(directory, "$name.cbz")
        try {
            var totalBytes = 0L
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)
                images.forEachIndexed { index, item ->
                    currentCoroutineContext().ensureActive()
                    val uri = Uri.parse(item.uri)
                    val type = context.contentResolver.openInputStream(uri)?.use(ImageUtil::findImageType)
                    require(type != null) { "A selected file is not a supported image" }
                    zip.putNextEntry(ZipEntry("${(index + 1).toString().padStart(6, '0')}.${type.extension}"))
                    val input = checkNotNull(context.contentResolver.openInputStream(uri))
                    input.use {
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = it.read(buffer)
                            if (count < 0) break
                            copied += count
                            totalBytes += count
                            check(totalBytes <= 2L * 1024 * 1024 * 1024) { "Image comic exceeds cache size limit" }
                            zip.write(buffer, 0, count)
                        }
                        check(item.sizeBytes == null || item.sizeBytes < 0 || copied == item.sizeBytes)
                    }
                    zip.closeEntry()
                    onProgress(index + 1)
                }
            }
            output
        } catch (error: Throwable) {
            deleteTemporary(context, output)
            throw error
        }
    }

    fun deleteTemporary(context: Context, file: File) {
        val root = File(context.cacheDir, "image-comic-import").canonicalFile
        val parent = file.canonicalFile.parentFile ?: return
        if (parent.parentFile != root || !parent.name.startsWith("comic-") || file.extension != "cbz") return
        file.delete()
        parent.delete()
    }
}

package koharia.importing

import android.content.Context
import android.content.Intent
import android.net.Uri
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import koharia.domain.manga.model.toDomainManga
import koharia.epub.EpubReaderLauncher
import koharia.media.LocalMediaFormats
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

class IncomingMediaOpenManager(
    private val context: Context,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val epubReaderLauncher: EpubReaderLauncher,
) {

    suspend fun open(
        item: koharia.connection.ConnectionMediaImportItem,
        sourceId: Long,
    ): Result<Intent> {
        return try {
            Result.success(withIOContext { prepareOpenIntent(item, sourceId) })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private suspend fun prepareOpenIntent(
        item: koharia.connection.ConnectionMediaImportItem,
        sourceId: Long,
    ): Intent {
        require(item.extension in SUPPORTED_MEDIA_EXTENSIONS) { "Unsupported incoming media" }
        cleanExpiredSessions(sourceId)

        val sessionId = UUID.randomUUID().toString()
        val sessionDirectory = File(IncomingMediaSessionLocator.cacheRoot(context), sessionId)
        check(sessionDirectory.mkdirs()) { "Unable to create temporary reading session" }
        val fileName = safeFileName(item.displayName, item.extension)
        val target = File(sessionDirectory, fileName)
        val partial = File(sessionDirectory, "$fileName.partial")
        var createdMangaId: Long? = null

        try {
            val copied = context.contentResolver.openInputStream(Uri.parse(item.uri))?.use { input ->
                FileOutputStream(partial).use { output ->
                    input.copyTo(output)
                }
            } ?: error("Unable to open incoming media")
            require(copied > 0L) { "Incoming media is empty" }
            require(item.sizeBytes == null || item.sizeBytes < 0L || copied == item.sizeBytes) {
                "Incoming media size verification failed"
            }
            check(partial.renameTo(target)) { "Unable to finalize temporary reading session" }

            val now = System.currentTimeMillis()
            val mangaUrl = IncomingMediaSessionLocator.seriesUrl(sourceId, sessionId)
            val chapterUrl = IncomingMediaSessionLocator.chapterUrl(sourceId, sessionId, fileName)
            val title = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
            val remoteManga = eu.kanade.tachiyomi.source.model.SManga.create().apply {
                url = mangaUrl
                this.title = title
                initialized = true
            }.toDomainManga(sourceId).copy(
                dateAdded = now,
                lastUpdate = now,
            )
            val manga = mangaRepository.insertNetworkManga(listOf(remoteManga)).single()
            createdMangaId = manga.id
            val chapter = chapterRepository.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = chapterUrl,
                        name = title,
                        dateFetch = now,
                        dateUpload = now,
                        chapterNumber = 1.0,
                    ),
                ),
            ).singleOrNull() ?: error("Unable to create temporary chapter")
            sessionDirectory.setLastModified(now)

            val readerIntent = if (item.extension.equals("epub", ignoreCase = true)) {
                epubReaderLauncher.resolveIntent(context, manga.id, chapter.id)
            } else {
                ReaderActivity.newIntent(
                    context = context,
                    mangaId = manga.id,
                    chapterId = chapter.id,
                    sourceId = sourceId,
                    useEpubSettings = LocalMediaFormats.isReflowableBook(item.extension),
                )
            }
            return IncomingMediaNavigation.attachToReaderIntent(readerIntent, target)
        } catch (error: Throwable) {
            partial.delete()
            target.delete()
            sessionDirectory.delete()
            createdMangaId?.let { mangaId ->
                runCatching { mangaRepository.deleteMangaById(mangaId) }
            }
            throw error
        }
    }

    private suspend fun cleanExpiredSessions(sourceId: Long) {
        val root = IncomingMediaSessionLocator.cacheRoot(context)
        if (!root.exists()) return
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.lastModified() > 0L && it.lastModified() < cutoff }
            .forEach(File::deleteRecursively)
        val existingSessions = root.listFiles().orEmpty()
            .filter(File::isDirectory)
            .mapTo(mutableSetOf(), File::getName)
        mangaRepository.getMangaBySourceId(sourceId)
            .filter { manga ->
                val location = IncomingMediaSessionLocator.location(manga.url, sourceId)
                location != null && location.sessionId !in existingSessions
            }
            .forEach { manga -> mangaRepository.deleteMangaById(manga.id) }
    }

    private fun safeFileName(displayName: String, extension: String): String {
        val normalized = displayName.substringAfterLast('/')
            .replace(Regex("[\\u0000-\\u001f\\\\/:*?\"<>|]"), "_")
            .trim()
            .takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: "Incoming media.$extension"
        val baseName = if (normalized.substringAfterLast('.', "").equals(extension, ignoreCase = true)) {
            normalized.substringBeforeLast('.')
        } else {
            normalized
        }
        return "${baseName.take(MAX_FILE_NAME_BASE_LENGTH).ifBlank { "Incoming media" }}.$extension"
    }

    private companion object {
        const val MAX_FILE_NAME_BASE_LENGTH = 180
    }
}

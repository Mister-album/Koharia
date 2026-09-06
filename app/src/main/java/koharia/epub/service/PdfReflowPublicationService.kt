package koharia.epub.service

import android.app.Application
import eu.kanade.tachiyomi.data.download.DownloadProvider
import koharia.connection.ConnectionLocalFileAdapter
import koharia.connection.ConnectionRawDownloadAdapter
import koharia.connection.ConnectionSource
import koharia.komga.download.KomgaChapterMemo
import koharia.pdf.cache.PdfReflowArtifact
import koharia.pdf.cache.PdfReflowCacheManager
import koharia.pdf.cache.PdfRemoteSource
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class PdfReflowPublicationService(
    private val application: Application = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val cache: PdfReflowCacheManager = Injekt.get(),
) {
    suspend fun prepare(
        source: ConnectionSource,
        manga: Manga,
        chapter: Chapter,
        ephemeral: Boolean,
        initialPage: Int = chapter.lastPageRead.toInt(),
    ): PdfReflowArtifact {
        val localFile = (source as? ConnectionLocalFileAdapter)?.localChapterFile(chapter.url)
        val file = (
            localFile ?: downloadProvider.findChapterDir(
                chapterName = chapter.name,
                chapterScanlator = chapter.scanlator,
                chapterUrl = chapter.url,
                mangaTitle = manga.title,
                source = source,
            )
            )?.takeIf { !it.isDirectory && it.extension.equals("pdf", true) }
        val remote = if (file == null) {
            (source as? ConnectionRawDownloadAdapter)?.let { adapter ->
                val fingerprint = KomgaChapterMemo.readFingerprint(chapter.memo)
                PdfRemoteSource(
                    adapter,
                    chapter.url,
                    fingerprint?.fileHash?.takeIf(String::isNotBlank)?.let { "$it:${fingerprint.sizeBytes}" },
                )
            }
        } else {
            null
        }
        check(file != null || remote != null) { application.stringResource(MR.strings.pdf_reflow_failed) }
        return try {
            cache.prepareProgressive(source.id, chapter.id, file, chapter.name, initialPage, ephemeral, remote)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException(application.stringResource(MR.strings.pdf_reflow_failed), error)
        }
    }
}

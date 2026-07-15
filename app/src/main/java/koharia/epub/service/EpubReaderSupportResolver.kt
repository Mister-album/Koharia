package koharia.epub.service

import android.app.Application
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.util.system.isOnline
import koharia.epub.model.EpubOpenRequest
import koharia.komga.api.dto.isDivinaCompatibleEpub
import koharia.komga.api.dto.isEpub
import koharia.source.komga.KomgaSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class EpubReaderSupportResolver @JvmOverloads constructor(
    private val application: Application = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
) {

    suspend fun resolve(
        mangaId: Long,
        chapterId: Long,
        preferLocalFile: Boolean,
    ): EpubReaderSupportResolution = withIOContext {
        val manga = getManga.await(mangaId) ?: return@withIOContext EpubReaderSupportResolution(
            mangaId = mangaId,
            chapterId = chapterId,
            unsupportedReason = EpubReaderSupportResolution.UnsupportedReason.MANGA_NOT_FOUND,
        )
        val chapter = getChapter.await(chapterId) ?: return@withIOContext EpubReaderSupportResolution(
            mangaId = mangaId,
            chapterId = chapterId,
            mangaTitle = manga.title,
            unsupportedReason = EpubReaderSupportResolution.UnsupportedReason.CHAPTER_NOT_FOUND,
        )
        val source = sourceManager.get(manga.source) as? KomgaSource
            ?: return@withIOContext EpubReaderSupportResolution(
                mangaId = manga.id,
                chapterId = chapter.id,
                mangaTitle = manga.title,
                chapterTitle = chapter.name,
                chapterRead = chapter.read,
                unsupportedReason = EpubReaderSupportResolution.UnsupportedReason.SOURCE_UNSUPPORTED,
            )

        val localUri = downloadProvider.findChapterDir(
            chapterName = chapter.name,
            chapterScanlator = chapter.scanlator,
            chapterUrl = chapter.url,
            mangaTitle = manga.title,
            source = source,
        )
            ?.takeIf { it.extension.equals("epub", ignoreCase = true) }
            ?.uri
            ?.toString()

        val remoteLookup = if (localUri != null && !application.isOnline()) {
            Result.success(null)
        } else {
            runCatching { source.getBookDetails(chapter.url) }
        }
        val remoteBook = remoteLookup.getOrNull()
        val remoteBookUrl = remoteBook
            ?.takeIf { it.isEpub }
            ?.let { chapter.url.substringBefore('#').removeSuffix("/") }

        val isDivinaCompatible = remoteBook?.media?.isDivinaCompatibleEpub == true

        val preferredOpenSource = when {
            preferLocalFile && localUri != null -> EpubOpenRequest.OpenSource.LOCAL
            remoteBookUrl != null -> EpubOpenRequest.OpenSource.REMOTE
            localUri != null -> EpubOpenRequest.OpenSource.LOCAL
            else -> null
        }

        val unsupportedReason = when {
            preferredOpenSource != null -> null
            remoteLookup.isSuccess -> EpubReaderSupportResolution.UnsupportedReason.NOT_EPUB
            else -> EpubReaderSupportResolution.UnsupportedReason.REMOTE_METADATA_UNAVAILABLE
        }

        EpubReaderSupportResolution(
            mangaId = manga.id,
            chapterId = chapter.id,
            sourceId = source.id,
            mangaTitle = manga.title,
            chapterTitle = chapter.name,
            chapterRead = chapter.read,
            localUri = localUri,
            remoteBookUrl = remoteBookUrl,
            isDivinaCompatible = isDivinaCompatible,
            preferredOpenSource = preferredOpenSource,
            unsupportedReason = unsupportedReason,
            metadataError = remoteLookup.exceptionOrNull(),
        )
    }
}

data class EpubReaderSupportResolution(
    val mangaId: Long,
    val chapterId: Long,
    val sourceId: Long = 0L,
    val mangaTitle: String? = null,
    val chapterTitle: String? = null,
    val chapterRead: Boolean = false,
    val localUri: String? = null,
    val remoteBookUrl: String? = null,
    val isDivinaCompatible: Boolean = false,
    val preferredOpenSource: EpubOpenRequest.OpenSource? = null,
    val unsupportedReason: UnsupportedReason? = null,
    val metadataError: Throwable? = null,
) {

    val isNativeSupported: Boolean
        get() = preferredOpenSource != null

    fun toOpenRequest(): EpubOpenRequest? {
        val openSource = preferredOpenSource ?: return null
        return EpubOpenRequest(
            mangaId = mangaId,
            chapterId = chapterId,
            sourceId = sourceId,
            title = chapterTitle.orEmpty(),
            bookUrl = remoteBookUrl,
            localUri = localUri,
            openSource = openSource,
        )
    }

    fun unsupportedMessage(application: Application): String {
        return when (unsupportedReason) {
            UnsupportedReason.MANGA_NOT_FOUND -> application.stringResource(MR.strings.epub_reader_manga_not_found)
            UnsupportedReason.CHAPTER_NOT_FOUND -> application.stringResource(MR.strings.chapter_not_found)
            UnsupportedReason.SOURCE_UNSUPPORTED -> application.stringResource(MR.strings.source_unsupported)
            UnsupportedReason.NOT_EPUB -> application.stringResource(MR.strings.epub_reader_unsupported_book)
            UnsupportedReason.REMOTE_METADATA_UNAVAILABLE -> {
                application.stringResource(MR.strings.epub_reader_remote_metadata_unavailable)
            }
            null -> application.stringResource(MR.strings.epub_reader_open_failed)
        }
    }

    enum class UnsupportedReason {
        MANGA_NOT_FOUND,
        CHAPTER_NOT_FOUND,
        SOURCE_UNSUPPORTED,
        NOT_EPUB,
        REMOTE_METADATA_UNAVAILABLE,
    }
}

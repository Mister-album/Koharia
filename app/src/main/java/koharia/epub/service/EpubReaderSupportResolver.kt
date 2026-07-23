package koharia.epub.service

import android.app.Application
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.util.system.isOnline
import koharia.epub.cache.EpubCacheManager
import koharia.epub.cache.EpubCachePolicy
import koharia.epub.model.EpubOpenRequest
import koharia.komga.api.dto.isDivinaCompatibleEpub
import koharia.komga.api.dto.isEpub
import koharia.komga.download.KomgaChapterMemo
import koharia.source.komga.KomgaSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
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
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val epubCacheManager: EpubCacheManager = Injekt.get(),
) {

    suspend fun resolve(
        mangaId: Long,
        chapterId: Long,
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

        val downloadedFile = downloadProvider.findChapterDir(
            chapterName = chapter.name,
            chapterScanlator = chapter.scanlator,
            chapterUrl = chapter.url,
            mangaTitle = manga.title,
            source = source,
        )
            ?.takeIf { it.extension.equals("epub", ignoreCase = true) }
        val downloadedUri = downloadedFile?.uri?.toString()

        val memoFingerprint = KomgaChapterMemo.readFingerprint(chapter.memo)
        val memoIsEpub = KomgaChapterMemo.isEpub(chapter.memo)
        val memoIsDivinaCompatible = KomgaChapterMemo.isEpubDivinaCompatible(chapter.memo)
        val memoPagesCount = KomgaChapterMemo.pagesCount(chapter.memo) ?: 0
        val memoBookUrl = memoFingerprint?.bookUrl
            ?: chapter.url.substringBefore('#').removeSuffix("/")
        val remotePublicationKey = EpubCachePolicy.publicationKey(
            fileHash = memoFingerprint?.fileHash,
            fileLastModified = KomgaChapterMemo.fileLastModified(chapter.memo),
            sizeBytes = memoFingerprint?.sizeBytes ?: 0L,
            fallback = "book:${chapter.id}:${chapter.url}",
        )
        val cachedBookFile = epubCacheManager.completeBookFile(source.id, remotePublicationKey)
        val cachedBookUri = cachedBookFile?.toURI()?.toString()
        val selectedSource = EpubCachePolicy.selectOpenSource(downloadedUri, cachedBookUri, memoBookUrl)
        val localUri = when (selectedSource) {
            EpubCachePolicy.OpenSource.MANUAL_DOWNLOAD -> downloadedUri
            EpubCachePolicy.OpenSource.COMPLETE_CACHE -> cachedBookUri
            else -> null
        }

        val needsRemoteClassification = !KomgaChapterMemo.hasCompleteEpubClassification(chapter.memo)
        val willRequestRemoteClassification = needsRemoteClassification && application.isOnline()
        val remoteLookup = if (!willRequestRemoteClassification) {
            Result.success(null)
        } else {
            runCatching { source.getBookDetails(chapter.url) }
        }
        val remoteBook = remoteLookup.getOrNull()
        if (remoteBook != null) {
            val updatedMemo = KomgaChapterMemo.mergeInto(
                existing = chapter.memo,
                baseUrl = source.baseUrl.trimEnd('/'),
                book = remoteBook,
            )
            if (updatedMemo != chapter.memo) {
                updateChapter.await(ChapterUpdate(id = chapter.id, memo = updatedMemo))
            }
        }
        val resolvedRemotePublicationKey = remoteBook?.let { book ->
            EpubCachePolicy.publicationKey(
                fileHash = book.fileHash,
                fileLastModified = book.fileLastModified,
                sizeBytes = book.sizeBytes,
                fallback = remotePublicationKey,
            )
        } ?: remotePublicationKey
        val remoteBookUrl = when {
            remoteBook?.isEpub == true -> chapter.url.substringBefore('#').removeSuffix("/")
            memoIsEpub == true || localUri != null -> memoBookUrl
            else -> null
        }

        val isDivinaCompatible = remoteBook?.media?.let { media ->
            media.isDivinaCompatibleEpub && media.pagesCount > 0
        } ?: (memoIsEpub == true && memoIsDivinaCompatible == true && memoPagesCount > 0)

        val preferredOpenSource = when {
            localUri != null -> EpubOpenRequest.OpenSource.LOCAL
            remoteBookUrl != null -> EpubOpenRequest.OpenSource.REMOTE
            else -> null
        }

        val unsupportedReason = when {
            preferredOpenSource != null -> null
            remoteLookup.isSuccess -> EpubReaderSupportResolution.UnsupportedReason.NOT_EPUB
            else -> EpubReaderSupportResolution.UnsupportedReason.REMOTE_METADATA_UNAVAILABLE
        }

        val resolution = EpubReaderSupportResolution(
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
            publicationKey = when {
                downloadedFile != null ->
                    "local:$downloadedUri:${downloadedFile.lastModified()}:${downloadedFile.length()}"
                else -> resolvedRemotePublicationKey
            },
            bookFileName = downloadedFile?.name
                ?: cachedBookFile?.name
                ?: remoteBook?.name
                ?: KomgaChapterMemo.fileName(chapter.memo),
            bookSizeBytes = downloadedFile?.length()?.takeIf { it > 0L }
                ?: cachedBookFile?.length()?.takeIf { it > 0L }
                ?: remoteBook?.sizeBytes?.takeIf { it > 0L }
                ?: memoFingerprint?.sizeBytes?.takeIf { it > 0L },
            isManualDownload = downloadedFile != null,
            isCompleteCache = downloadedFile == null && cachedBookFile != null,
        )
        logcat {
            "MangaStartup: reader route resolved chapterId=${chapter.id} " +
                "memoType=$memoIsEpub memoDivina=$memoIsDivinaCompatible " +
                "metadataRequested=$willRequestRemoteClassification divina=${resolution.shouldOpenAsPages} " +
                "nativeEpub=${resolution.isNativeSupported}"
        }
        resolution
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
    val publicationKey: String? = null,
    val bookFileName: String? = null,
    val bookSizeBytes: Long? = null,
    val isManualDownload: Boolean = false,
    val isCompleteCache: Boolean = false,
) {

    val isNativeSupported: Boolean
        get() = preferredOpenSource != null

    val shouldOpenAsPages: Boolean
        get() = isNativeSupported && isDivinaCompatible

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
            publicationKey = publicationKey ?: "chapter:$chapterId",
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

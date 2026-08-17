package koharia.epub.service

import android.app.Application
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.util.system.isOnline
import koharia.connection.ConnectionLocalFileAdapter
import koharia.connection.ConnectionPublicationAdapter
import koharia.connection.ConnectionPublicationMetadata
import koharia.connection.ConnectionSource
import koharia.epub.cache.EpubCacheManager
import koharia.epub.cache.EpubCachePolicy
import koharia.epub.model.EpubOpenRequest
import koharia.epub.model.RemotePublicationRef
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
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
        val source = sourceManager.get(manga.source) as? ConnectionSource
        if (source == null) {
            return@withIOContext EpubReaderSupportResolution(
                mangaId = manga.id,
                chapterId = chapter.id,
                mangaTitle = manga.title,
                chapterTitle = chapter.name,
                chapterRead = chapter.read,
                unsupportedReason = EpubReaderSupportResolution.UnsupportedReason.SOURCE_UNSUPPORTED,
            )
        }

        val localPublicationFile = (source as? ConnectionLocalFileAdapter)
            ?.localChapterFile(chapter.url)
            ?.takeIf { file -> !file.isDirectory && file.extension.equals("epub", ignoreCase = true) }
        val publicationAdapter = source as? ConnectionPublicationAdapter
        if (localPublicationFile == null && publicationAdapter == null) {
            return@withIOContext EpubReaderSupportResolution(
                mangaId = manga.id,
                chapterId = chapter.id,
                mangaTitle = manga.title,
                chapterTitle = chapter.name,
                chapterRead = chapter.read,
                unsupportedReason = EpubReaderSupportResolution.UnsupportedReason.SOURCE_UNSUPPORTED,
            )
        }

        val downloadedFile = if (localPublicationFile == null) {
            downloadProvider.findChapterDir(
                chapterName = chapter.name,
                chapterScanlator = chapter.scanlator,
                chapterUrl = chapter.url,
                mangaTitle = manga.title,
                source = source,
            )
                ?.takeIf { it.extension.equals("epub", ignoreCase = true) }
        } else {
            null
        }
        val localPublicationUri = localPublicationFile?.uri?.toString()
        val downloadedUri = downloadedFile?.uri?.toString()

        val metadata = if (localPublicationFile != null) {
            ConnectionPublicationMetadata(
                remoteResourceId = null,
                publicationKey = localPublicationKey(
                    uri = checkNotNull(localPublicationUri),
                    modifiedAt = localPublicationFile.lastModified(),
                    sizeBytes = localPublicationFile.length(),
                ),
                isPageCompatible = false,
                fileName = localPublicationFile.name,
                sizeBytes = localPublicationFile.length().takeIf { it > 0L },
            )
        } else {
            checkNotNull(publicationAdapter).resolvePublication(
                chapter = chapter,
                allowRemoteLookup = application.isOnline(),
            )
        }
        val cachedBookFile = epubCacheManager.completeBookFile(source.id, metadata.publicationKey)
        val cachedBookUri = cachedBookFile?.toURI()?.toString()
        val selectedSource = EpubCachePolicy.selectOpenSource(
            downloadedUri,
            cachedBookUri,
            metadata.remoteResourceId,
        )
        val localUri = localPublicationUri ?: when (selectedSource) {
            EpubCachePolicy.OpenSource.MANUAL_DOWNLOAD -> downloadedUri
            EpubCachePolicy.OpenSource.COMPLETE_CACHE -> cachedBookUri
            else -> null
        }

        val remoteBookUrl = metadata.remoteResourceId

        val preferredOpenSource = when {
            localUri != null -> EpubOpenRequest.OpenSource.LOCAL
            remoteBookUrl != null -> EpubOpenRequest.OpenSource.REMOTE
            else -> null
        }

        val unsupportedReason = when {
            preferredOpenSource != null -> null
            metadata.metadataError == null -> EpubReaderSupportResolution.UnsupportedReason.NOT_EPUB
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
            providerId = source.providerId,
            isDivinaCompatible = metadata.isPageCompatible,
            preferredOpenSource = preferredOpenSource,
            unsupportedReason = unsupportedReason,
            metadataError = metadata.metadataError,
            publicationKey = when {
                localPublicationFile != null -> metadata.publicationKey
                downloadedFile != null ->
                    "local:$downloadedUri:${downloadedFile.lastModified()}:${downloadedFile.length()}"
                else -> metadata.publicationKey
            },
            bookFileName = localPublicationFile?.name
                ?: downloadedFile?.name
                ?: cachedBookFile?.name
                ?: metadata.fileName,
            bookSizeBytes = localPublicationFile?.length()?.takeIf { it > 0L }
                ?: downloadedFile?.length()?.takeIf { it > 0L }
                ?: cachedBookFile?.length()?.takeIf { it > 0L }
                ?: metadata.sizeBytes,
            isManualDownload = downloadedFile != null,
            isCompleteCache = localPublicationFile == null && downloadedFile == null && cachedBookFile != null,
        )
        logcat {
            "MangaStartup: reader route resolved chapterId=${chapter.id} " +
                "provider=${source.providerId} divina=${resolution.shouldOpenAsPages} " +
                "nativeEpub=${resolution.isNativeSupported}"
        }
        resolution
    }
}

internal fun localPublicationKey(
    uri: String,
    modifiedAt: Long,
    sizeBytes: Long,
): String = "local:$uri:$modifiedAt:$sizeBytes"

data class EpubReaderSupportResolution(
    val mangaId: Long,
    val chapterId: Long,
    val sourceId: Long = 0L,
    val providerId: String? = null,
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
            remotePublication = remoteBookUrl?.let { resourceId ->
                RemotePublicationRef(
                    providerId = checkNotNull(providerId),
                    resourceId = resourceId,
                )
            },
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

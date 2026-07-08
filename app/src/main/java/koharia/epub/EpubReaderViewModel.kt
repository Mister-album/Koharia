package koharia.epub

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.DownloadProvider
import koharia.domain.epub.interactor.GetEpubProgress
import koharia.domain.epub.interactor.UpsertEpubProgress
import koharia.domain.epub.model.EpubProgress
import koharia.epub.model.EpubOpenRequest
import koharia.epub.model.EpubTocEntry
import koharia.epub.progress.KomgaEpubProgressSyncService
import koharia.epub.service.EpubPublicationResolver
import koharia.epub.session.EpubReaderSessionRepository
import koharia.epub.settings.EpubReaderPreferences
import koharia.komga.api.dto.isEpub
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
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
import java.util.Date

class EpubReaderViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val application: Application = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val publicationResolver: EpubPublicationResolver = Injekt.get(),
    private val sessionRepository: EpubReaderSessionRepository = Injekt.get(),
    private val getEpubProgress: GetEpubProgress = Injekt.get(),
    private val upsertEpubProgress: UpsertEpubProgress = Injekt.get(),
    private val komgaEpubProgressSyncService: KomgaEpubProgressSyncService = Injekt.get(),
    private val epubReaderPreferences: EpubReaderPreferences = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
) : ViewModel() {

    private val mutableState = MutableStateFlow(EpubReaderUiState())
    val state = mutableState.asStateFlow()
    private val locatorUpdates = MutableSharedFlow<Locator>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var mangaId = savedState.get<Long>("manga_id") ?: -1L
        set(value) {
            savedState["manga_id"] = value
            field = value
        }

    private var chapterId = savedState.get<Long>("chapter_id") ?: -1L
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    private var locatorJson = savedState.get<String>("locator_json")
        set(value) {
            savedState["locator_json"] = value
            field = value
        }

    private var currentBookUrl: String? = null
    private var currentSourceId: Long = -1L
    private var currentProgress: EpubProgress? = null
    private var latestLocator: Locator? = null

    init {
        viewModelScope.launch {
            locatorUpdates
                .debounce(750L)
                .collectLatest(::persistLocator)
        }
    }

    fun needsInit(): Boolean = !state.value.isLoading && !state.value.isReady

    suspend fun init(
        mangaId: Long,
        chapterId: Long,
    ): Result<Unit> {
        this.mangaId = mangaId
        this.chapterId = chapterId
        mutableState.update {
            it.copy(
                mangaId = mangaId,
                chapterId = chapterId,
                isLoading = true,
                isReady = false,
                errorMessage = null,
            )
        }

        return withIOContext {
            runCatching {
                val manga = checkNotNull(getManga.await(mangaId)) { "Manga not found" }
                val chapter = checkNotNull(getChapter.await(chapterId)) { "Chapter not found" }
                val source = sourceManager.get(manga.source) as? KomgaSource
                    ?: error(application.stringResource(MR.strings.source_unsupported))
                currentSourceId = source.id

                val localFile = downloadProvider.findChapterDir(
                    chapterName = chapter.name,
                    chapterScanlator = chapter.scanlator,
                    chapterUrl = chapter.url,
                    mangaTitle = manga.title,
                    source = source,
                )
                val localUri = localFile
                    ?.takeIf { it.extension.equals("epub", ignoreCase = true) }
                    ?.uri
                    ?.toString()

                val remoteBookUrl = runCatching { source.getBookDetails(chapter.url) }
                    .getOrNull()
                    ?.takeIf { it.isEpub }
                    ?.let { chapter.url.substringBefore('#').removeSuffix("/") }

                check(localUri != null || remoteBookUrl != null) {
                    application.stringResource(MR.strings.epub_reader_unsupported_book)
                }

                val primarySource = when {
                    epubReaderPreferences.preferLocalFile.get() && localUri != null ->
                        EpubOpenRequest.OpenSource.LOCAL
                    remoteBookUrl != null ->
                        EpubOpenRequest.OpenSource.REMOTE
                    else ->
                        EpubOpenRequest.OpenSource.LOCAL
                }
                logcat(LogPriority.DEBUG) {
                    "EPUB init chapterId=${chapter.id} localUri=$localUri remoteBookUrl=$remoteBookUrl primarySource=$primarySource incognito=${isIncognito()}"
                }

                currentBookUrl = remoteBookUrl

                val incognito = isIncognito()
                val localProgress = if (incognito) {
                    null
                } else {
                    getEpubProgress.await(chapter.id)
                }
                val remoteProgress = if (incognito || remoteBookUrl == null ||
                    !epubReaderPreferences.syncProgressionToKomga.get()
                ) {
                    null
                } else {
                    runCatching { komgaEpubProgressSyncService.pullProgression(source.id, remoteBookUrl) }
                        .onFailure { error ->
                            logcat(LogPriority.WARN, error) {
                                "Failed to pull Komga EPUB progression for chapterId=${chapter.id}"
                            }
                        }
                        .getOrNull()
                }
                val persistedLocator = localProgress?.toLocatorOrNull()
                val initialLocator = restoreLocator()
                    ?: chooseMoreRecentLocator(localProgress, remoteProgress)

                if (initialLocator != null) {
                    latestLocator = initialLocator
                    locatorJson = initialLocator.toJSON().toString()
                }

                val session = publicationResolver.open(
                    request = EpubOpenRequest(
                        mangaId = manga.id,
                        chapterId = chapter.id,
                        sourceId = source.id,
                        title = chapter.name,
                        bookUrl = remoteBookUrl,
                        localUri = localUri,
                        openSource = primarySource,
                    ),
                    initialLocator = initialLocator,
                )
                sessionRepository.put(session)
                logcat(LogPriority.DEBUG) {
                    "EPUB session ready chapterId=${chapter.id} readingOrder=${session.publication.readingOrder.size} toc=${session.publication.tableOfContents.size}"
                }
                latestLocator = initialLocator
                currentProgress = localProgress

                mutableState.update {
                    it.copy(
                        mangaId = manga.id,
                        chapterId = chapter.id,
                        mangaTitle = manga.title,
                        chapterTitle = chapter.name,
                        currentSectionTitle = initialLocator?.title,
                        progressionPercent = initialLocator?.progressionPercent(),
                        isLoading = false,
                        isReady = true,
                        menuVisible = true,
                        errorMessage = null,
                    )
                }

                if (!incognito) {
                    val selectedRemote = remoteProgress?.takeIf {
                        localProgress == null || it.modifiedAt.time > localProgress.updatedAt.time
                    }
                    if (selectedRemote != null) {
                        val syncedProgress = buildProgress(
                            locator = selectedRemote.locator,
                            updatedAt = selectedRemote.modifiedAt,
                            lastSyncedAt = selectedRemote.modifiedAt,
                        )
                        currentProgress = syncedProgress
                        upsertEpubProgress.await(syncedProgress)
                    } else if (remoteBookUrl != null && localProgress != null && persistedLocator != null &&
                        epubReaderPreferences.syncProgressionToKomga.get() &&
                        (remoteProgress == null || localProgress.updatedAt.time > remoteProgress.modifiedAt.time)
                    ) {
                        syncPersistedProgress(localProgress, persistedLocator)
                    }
                }
            }
                .onFailure { error ->
                    logcat(LogPriority.ERROR, error) {
                        "EPUB init failed chapterId=$chapterId mangaId=$mangaId"
                    }
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            isReady = false,
                            errorMessage =
                            error.message ?: application.stringResource(MR.strings.epub_reader_open_failed),
                        )
                    }
                }
        }
    }

    fun retry(): Pair<Long, Long>? {
        val mangaId = mangaId.takeIf { it > 0 } ?: return null
        val chapterId = chapterId.takeIf { it > 0 } ?: return null
        return mangaId to chapterId
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    fun updateLocator(locator: Locator) {
        latestLocator = locator
        val newLocatorJson = locator.toJSON().toString()
        locatorJson = newLocatorJson
        logcat(LogPriority.DEBUG) {
            "EPUB locator update chapterId=$chapterId href=${locator.href} progression=${locator.totalProgressionValue()} position=${locator.positionIndex()}"
        }
        mutableState.update {
            it.copy(
                currentSectionTitle = locator.title ?: it.currentSectionTitle,
                progressionPercent = locator.progressionPercent(),
            )
        }
        if (!isIncognito()) {
            locatorUpdates.tryEmit(locator)
        }
    }

    fun tableOfContents(): List<EpubTocEntry> {
        val session = sessionRepository.get(chapterId) ?: return emptyList()
        return flattenLinks(session.publication.tableOfContents)
    }

    fun isIncognito(): Boolean = basePreferences.incognitoMode.get()

    fun releaseSession() {
        latestLocator?.let { locator ->
            if (!isIncognito()) {
                viewModelScope.launch {
                    persistLocator(locator)
                }
            }
        }
        sessionRepository.remove(chapterId)?.close()
    }

    private fun restoreLocator(): Locator? =
        locatorJson?.let {
            runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull()
        }

    private suspend fun syncPersistedProgress(
        localProgress: EpubProgress,
        locator: Locator,
    ) {
        val bookUrl = localProgress.bookUrl ?: currentBookUrl ?: return
        val sourceId = currentSourceId.takeIf { it > 0 } ?: return
        runCatching {
            komgaEpubProgressSyncService.pushProgression(sourceId, bookUrl, locator, localProgress.updatedAt)
            val syncedProgress = localProgress.copy(lastSyncedAt = localProgress.updatedAt)
            currentProgress = syncedProgress
            upsertEpubProgress.await(syncedProgress)
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) {
                "Failed to sync existing Komga EPUB progression for chapterId=${localProgress.chapterId}"
            }
        }
    }

    private suspend fun persistLocator(locator: Locator) {
        val chapterId = chapterId.takeIf { it > 0 } ?: return
        val mangaId = mangaId.takeIf { it > 0 } ?: return
        val now = Date()
        val progress = buildProgress(
            locator = locator,
            updatedAt = now,
            lastSyncedAt = currentProgress?.lastSyncedAt,
        )
        upsertEpubProgress.await(progress)
        currentProgress = progress

        val bookUrl = progress.bookUrl
        val sourceId = currentSourceId.takeIf { it > 0 }
        if (bookUrl != null && sourceId != null && epubReaderPreferences.syncProgressionToKomga.get()) {
            runCatching {
                komgaEpubProgressSyncService.pushProgression(sourceId, bookUrl, locator, now)
                val syncedProgress = progress.copy(lastSyncedAt = now)
                upsertEpubProgress.await(syncedProgress)
                currentProgress = syncedProgress
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Failed to push Komga EPUB progression for chapterId=$chapterId"
                }
            }
        }
    }

    private fun buildProgress(
        locator: Locator,
        updatedAt: Date,
        lastSyncedAt: Date?,
    ): EpubProgress {
        return EpubProgress(
            chapterId = chapterId,
            mangaId = mangaId,
            bookUrl = currentBookUrl ?: currentProgress?.bookUrl,
            locatorJson = locator.toJSON().toString(),
            progression = locator.totalProgressionValue(),
            positionIndex = locator.positionIndex(),
            updatedAt = updatedAt,
            lastSyncedAt = lastSyncedAt,
        )
    }

    private fun chooseMoreRecentLocator(
        localProgress: EpubProgress?,
        remoteProgress: KomgaEpubProgressSyncService.RemoteProgression?,
    ): Locator? {
        val localLocator = localProgress?.toLocatorOrNull()
        return when {
            remoteProgress != null &&
                (localProgress == null || remoteProgress.modifiedAt.time > localProgress.updatedAt.time) ->
                remoteProgress.locator
            localLocator != null ->
                localLocator
            else ->
                remoteProgress?.locator
        }
    }

    private fun flattenLinks(
        links: List<Link>,
        depth: Int = 0,
    ): List<EpubTocEntry> {
        return links.flatMap { link ->
            buildList {
                add(
                    EpubTocEntry(
                        title = link.title?.takeIf(String::isNotBlank) ?: link.href.toString(),
                        link = link,
                        depth = depth,
                    ),
                )
                addAll(flattenLinks(link.children, depth + 1))
            }
        }
    }

    private fun EpubProgress.toLocatorOrNull(): Locator? {
        return runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull()
    }

    private fun Locator.progressionPercent(): Int? {
        return totalProgressionValue()?.times(100)?.toInt()
    }

    private fun Locator.totalProgressionValue(): Double? {
        return (locations.totalProgression as? Number)?.toDouble()
            ?: (locations.progression as? Number)?.toDouble()
    }

    private fun Locator.positionIndex(): Long? {
        return (locations.position as? Number)?.toLong()
    }
}

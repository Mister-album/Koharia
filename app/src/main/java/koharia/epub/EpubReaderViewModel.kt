package koharia.epub

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.DownloadProvider
import koharia.domain.epub.interactor.AddEpubBookmark
import koharia.domain.epub.interactor.DeleteEpubBookmark
import koharia.domain.epub.interactor.GetEpubBookmarks
import koharia.domain.epub.interactor.GetEpubPaginationCache
import koharia.domain.epub.interactor.GetEpubProgress
import koharia.domain.epub.interactor.UpdateEpubBookmarkNote
import koharia.domain.epub.interactor.UpsertEpubPaginationCache
import koharia.domain.epub.interactor.UpsertEpubProgress
import koharia.domain.epub.model.EpubBookmark
import koharia.domain.epub.model.EpubPaginationCache
import koharia.domain.epub.model.EpubProgress
import koharia.epub.locator.toPersistentLocator
import koharia.epub.model.EpubOpenRequest
import koharia.epub.model.EpubSearchResult
import koharia.epub.model.EpubTocEntry
import koharia.epub.progress.KomgaEpubProgressSyncService
import koharia.epub.service.EpubPublicationResolver
import koharia.epub.session.EpubReaderSessionRepository
import koharia.epub.settings.EpubReaderPreferences
import koharia.epub.settings.EpubSessionPreferenceStore
import koharia.komga.api.dto.isDivinaCompatibleEpub
import koharia.komga.api.dto.isEpub
import koharia.source.komga.KomgaScopedPreferenceStoreFactory
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import org.json.JSONObject
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.isSearchable
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val SERVER_TIME_WARNING_THRESHOLD_MS = 5 * 60 * 1_000L
private const val PAGINATION_LOCATOR_PROGRESSION_TOLERANCE = 0.0001

@OptIn(ExperimentalReadiumApi::class)
class EpubReaderViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val application: Application = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val publicationResolver: EpubPublicationResolver = Injekt.get(),
    private val sessionRepository: EpubReaderSessionRepository = Injekt.get(),
    private val getEpubProgress: GetEpubProgress = Injekt.get(),
    private val upsertEpubProgress: UpsertEpubProgress = Injekt.get(),
    private val getEpubPaginationCache: GetEpubPaginationCache = Injekt.get(),
    private val upsertEpubPaginationCache: UpsertEpubPaginationCache = Injekt.get(),
    private val komgaEpubProgressSyncService: KomgaEpubProgressSyncService = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val getEpubBookmarks: GetEpubBookmarks = Injekt.get(),
    private val addEpubBookmark: AddEpubBookmark = Injekt.get(),
    private val deleteEpubBookmark: DeleteEpubBookmark = Injekt.get(),
    private val updateEpubBookmarkNote: UpdateEpubBookmarkNote = Injekt.get(),
    globalEpubReaderPreferences: EpubReaderPreferences = Injekt.get(),
    globalBasePreferences: BasePreferences = Injekt.get(),
    private val scopedPreferenceStoreFactory: KomgaScopedPreferenceStoreFactory = Injekt.get(),
) : ViewModel() {

    private var epubReaderPreferences: EpubReaderPreferences =
        scopedPreferenceStoreFactory.epubReaderPreferencesForSavedSource(savedState) ?: globalEpubReaderPreferences
    private var basePreferences: BasePreferences =
        scopedPreferenceStoreFactory.basePreferencesForSavedSource(savedState) ?: globalBasePreferences
    private var transientReaderSettingsStore: PreferenceStore? = null
    private var publisherStylesOverride: Boolean? = null

    private companion object {
        val sessionReleaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private val mutableState = MutableStateFlow(EpubReaderUiState())
    val state = mutableState.asStateFlow()
    private val locatorUpdates = MutableSharedFlow<Locator>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val searchUpdates = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val progressPersistenceMutex = Mutex()
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
    private var publicationPositions: List<Locator> = emptyList()
    private var publicationPositionByHref: Map<String, Int> = emptyMap()
    private var visualPageNumber: Int? = null
    private var lastVisualHref: String? = null
    private var lastVisualPageIndex: Int? = null
    private var lastVisualTotalPages: Int? = null
    private var bookVisualPageCounts: Map<String, Int> = emptyMap()
    private var paginationGeneration = 0L
    private var paginationLayoutKey: String? = null
    private var paginationLayoutJson: String? = null
    private var currentPublicationKey: String? = null
    private var currentChapterUrl: String? = null
    private var currentChapterRead = false
    private var currentChapterBookmark = false
    private var historyReadStartTime: Long? = null
    private var completionMarkedThisSession = false
    private var searchIterator: SearchIterator? = null
    private var incognitoSession = basePreferences.incognitoMode.get()
    private val locatorPersistenceJob = viewModelScope.launch {
        locatorUpdates
            .debounce(750L)
            .collect(::persistLocator)
    }

    init {
        viewModelScope.launch {
            searchUpdates
                .collectLatest(::performSearch)
        }
    }

    fun needsInit(): Boolean = !state.value.isLoading && !state.value.isReady

    internal fun readerSettingsStore(
        backingStore: PreferenceStore,
        persistChanges: Boolean,
    ): PreferenceStore {
        return transientReaderSettingsStore
            ?: EpubSessionPreferenceStore(backingStore, persistChanges).also { transientReaderSettingsStore = it }
    }

    internal fun setPersistReaderSettingsChanges(enabled: Boolean) {
        (transientReaderSettingsStore as? EpubSessionPreferenceStore)?.setPersistChanges(enabled)
    }

    internal fun setPublisherStylesOverride(enabled: Boolean?) {
        publisherStylesOverride = enabled
    }

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
                bookFileName = null,
                bookSizeBytes = null,
                localEpubUri = null,
                isUsingLocalFile = false,
                canOpenAsPages = false,
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

                savedState["source_id"] = source.id
                currentSourceId = source.id
                epubReaderPreferences = scopedPreferenceStoreFactory.epubReaderPreferences(source.id)
                basePreferences = scopedPreferenceStoreFactory.basePreferences(source.id)
                incognitoSession = basePreferences.incognitoMode.get()
                currentChapterUrl = chapter.url
                currentChapterRead = chapter.read
                currentChapterBookmark = chapter.bookmark
                completionMarkedThisSession = chapter.read
                historyReadStartTime = if (isIncognito()) null else System.currentTimeMillis()

                val chapters = getChaptersByMangaId.await(mangaId, applyScanlatorFilter = true)
                    .sortedWith(getChapterSort(manga, sortDescending = false))
                val chapterIndex = chapters.indexOfFirst { it.id == chapter.id }
                val previousBookChapterId = chapterIndex.takeIf { it >= 0 }
                    ?.let { chapters.getOrNull(it - 1)?.id }
                val nextBookChapterId = chapterIndex.takeIf { it >= 0 }
                    ?.let { chapters.getOrNull(it + 1)?.id }

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

                val bookDetails = runCatching { source.getBookDetails(chapter.url) }
                    .getOrNull()
                    ?.takeIf { it.isEpub }
                val remoteBookUrl = bookDetails
                    ?.let { chapter.url.substringBefore('#').removeSuffix("/") }
                val canOpenAsPages = bookDetails?.media?.let { media ->
                    media.isDivinaCompatibleEpub && media.pagesCount > 0
                } == true

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
                val bookFileName = localFile?.name ?: bookDetails?.name
                val bookSizeBytes = localFile?.length()?.takeIf { size -> size > 0L }
                    ?: bookDetails?.sizeBytes?.takeIf { size -> size > 0L }
                mutableState.update {
                    it.copy(
                        mangaTitle = manga.title,
                        chapterTitle = chapter.name,
                        bookFileName = bookFileName,
                        bookSizeBytes = bookSizeBytes,
                        localEpubUri = localUri,
                        isUsingLocalFile = primarySource == EpubOpenRequest.OpenSource.LOCAL,
                        canOpenAsPages = canOpenAsPages,
                    )
                }
                logcat(LogPriority.DEBUG) {
                    "EPUB init chapterId=${chapter.id} localUri=$localUri remoteBookUrl=$remoteBookUrl primarySource=$primarySource incognito=${isIncognito()}"
                }

                currentBookUrl = remoteBookUrl
                currentPublicationKey = when {
                    !bookDetails?.fileHash.isNullOrBlank() -> "komga:${bookDetails.fileHash}"
                    bookDetails != null -> "komga:${bookDetails.fileLastModified}:${bookDetails.sizeBytes}"
                    localFile != null -> "local:$localUri:${localFile.lastModified()}:${localFile.length()}"
                    else -> "book:${chapter.id}:${chapter.url}"
                }

                val incognito = isIncognito()
                val localProgress = if (incognito) {
                    null
                } else {
                    runCatching { getEpubProgress.await(chapter.id) }
                        .onFailure { error ->
                            logcat(LogPriority.WARN, error) {
                                "Failed to load EPUB local progress for chapterId=${chapter.id}"
                            }
                        }
                        .getOrNull()
                }
                val remotePull = if (incognito || remoteBookUrl == null ||
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
                val remoteProgress = remotePull?.progression
                val serverTimeOffsetMinutes = remotePull?.serverDate?.serverTimeOffsetMinutes()
                val persistedLocator = localProgress?.toLocatorOrNull()
                val savedStateLocator = restoreLocator()
                val initialLocator = savedStateLocator
                    ?: chooseMoreRecentLocator(localProgress, remoteProgress)

                logcat(LogPriority.DEBUG) {
                    "EPUB progress restore chapterId=${chapter.id} " +
                        "savedState=${savedStateLocator.debugProgress()} " +
                        "local=${localProgress?.toLocatorOrNull().debugProgress()} " +
                        "localUpdated=${localProgress?.updatedAt?.time} " +
                        "remote=${remoteProgress?.locator.debugProgress()} " +
                        "remoteUpdated=${remoteProgress?.modifiedAt?.time} " +
                        "selected=${initialLocator.debugProgress()}"
                }

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
                        publisherStylesOverride = publisherStylesOverride,
                    ),
                    initialLocator = initialLocator,
                )
                sessionRepository.put(session)
                publicationPositions = session.publication.positions()
                publicationPositionByHref = buildMap {
                    publicationPositions.forEachIndexed { index, locator ->
                        locator.href.toString().hrefCandidates().forEach { href ->
                            putIfAbsent(href, index + 1)
                        }
                    }
                }
                resetVisualPagination()
                val initialPosition = initialLocator.positionIn(publicationPositions)
                val initialProgression = initialLocator?.totalProgressionValue()
                    ?: initialPosition.toProgression(publicationPositions.size)
                logcat(LogPriority.DEBUG) {
                    "EPUB session ready chapterId=${chapter.id} readingOrder=${session.publication.readingOrder.size} toc=${session.publication.tableOfContents.size}"
                }
                latestLocator = initialLocator
                currentProgress = localProgress
                val bookmarks = if (incognito) {
                    emptyList()
                } else {
                    runCatching { getEpubBookmarks.await(chapter.id) }
                        .onFailure { error ->
                            logcat(LogPriority.WARN, error) {
                                "Failed to load EPUB bookmarks for chapterId=${chapter.id}"
                            }
                        }
                        .getOrDefault(emptyList())
                }

                mutableState.update {
                    it.copy(
                        mangaId = manga.id,
                        chapterId = chapter.id,
                        mangaTitle = manga.title,
                        chapterTitle = chapter.name,
                        bookFileName = bookFileName,
                        bookSizeBytes = bookSizeBytes,
                        localEpubUri = localUri,
                        isUsingLocalFile = primarySource == EpubOpenRequest.OpenSource.LOCAL,
                        canOpenAsPages = canOpenAsPages,
                        previousBookChapterId = previousBookChapterId,
                        nextBookChapterId = nextBookChapterId,
                        currentSectionTitle = initialLocator?.title,
                        currentHref = initialLocator?.navigationHref(),
                        progression = initialProgression,
                        progressionPercent = initialLocator?.progressionPercent(),
                        currentPosition = initialPosition,
                        totalPositions = publicationPositions.size.coerceAtLeast(1),
                        currentVisualPage = null,
                        totalVisualPages = null,
                        paginationPhase = EpubPaginationPhase.CALCULATING,
                        sessionToken = it.sessionToken + 1,
                        isLoading = false,
                        isReady = true,
                        menuVisible = true,
                        errorMessage = null,
                        serverTimeOffsetMinutes = serverTimeOffsetMinutes,
                        isIncognito = incognito,
                        bookmarks = bookmarks,
                        currentBookmarkId = findBookmarkForLocator(bookmarks, initialLocator)?.id,
                        isSearchable = session.publication.isSearchable,
                        searchResults = emptyList(),
                        isSearchLoading = false,
                        searchErrorMessage = null,
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

    suspend fun resolveDetailsMangaId(): Long? {
        val sourceId = currentSourceId.takeIf { it > 0 } ?: return mangaId.takeIf { it > 0 }
        val chapterUrl = currentChapterUrl?.substringBefore('#')?.removeSuffix("/")
        val matchedBookMangaId = chapterUrl
            ?.let { getMangaByUrlAndSourceId.await(it, sourceId)?.id }
            ?.takeIf { it > 0 }
        return matchedBookMangaId ?: mangaId.takeIf { it > 0 }
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    fun updateLocator(locator: Locator) {
        val persistentLocator = sessionRepository.get(chapterId)
            ?.publication
            ?.toPersistentLocator(locator)
            ?: locator
        latestLocator = persistentLocator
        val newLocatorJson = persistentLocator.toJSON().toString()
        locatorJson = newLocatorJson
        logcat(LogPriority.DEBUG) {
            "EPUB locator update chapterId=$chapterId navigatorHref=${locator.href} " +
                persistentLocator.debugProgress()
        }
        mutableState.update {
            it.copy(
                currentSectionTitle = persistentLocator.title ?: it.currentSectionTitle,
                currentHref = persistentLocator.navigationHref(),
                progression = persistentLocator.totalProgressionValue()
                    ?: persistentLocator.positionIn(publicationPositions).toProgression(publicationPositions.size),
                progressionPercent = persistentLocator.progressionPercent(),
                currentPosition = persistentLocator.positionIn(publicationPositions),
                currentBookmarkId = findBookmarkForLocator(it.bookmarks, persistentLocator)?.id,
            )
        }
        if (!isIncognito()) {
            locatorUpdates.tryEmit(persistentLocator)
        }
    }

    fun updateVisualPage(pageIndex: Int, totalPages: Int, locator: Locator) {
        if (pageIndex < 0 || totalPages <= 0 || publicationPositions.isEmpty()) return

        val href = locator.href.toString().normalizedResourceHref()
        lastVisualHref = href
        lastVisualPageIndex = pageIndex
        lastVisualTotalPages = totalPages

        val readingOrder = sessionRepository.get(chapterId)?.publication?.readingOrder.orEmpty()
        val exactPage = exactVisualPage(href, pageIndex, readingOrder, bookVisualPageCounts)
        val exactTotalPages = bookVisualPageCounts.values.sum()
            .takeIf { bookVisualPageCounts.size == readingOrder.size && it > 0 }
        if (exactPage != null && exactTotalPages != null) {
            val currentPage = exactPage.coerceIn(1, exactTotalPages)
            visualPageNumber = currentPage
            val previousState = mutableState.value
            if (previousState.paginationPhase != EpubPaginationPhase.READY ||
                previousState.totalVisualPages != exactTotalPages
            ) {
                logcat(LogPriority.DEBUG) {
                    "EPUB pagination visual page resolved chapterId=$chapterId " +
                        "from=${previousState.paginationPhase} " +
                        "pages=${previousState.currentVisualPage}/${previousState.totalVisualPages} " +
                        "to=$currentPage/$exactTotalPages"
                }
            }
            mutableState.update {
                it.copy(
                    currentVisualPage = currentPage,
                    totalVisualPages = exactTotalPages,
                    paginationPhase = EpubPaginationPhase.READY,
                )
            }
            return
        }
    }

    fun invalidatePaginationDisplay() {
        paginationGeneration += 1
        bookVisualPageCounts = emptyMap()
        visualPageNumber = null
        lastVisualHref = null
        lastVisualPageIndex = null
        lastVisualTotalPages = null
        paginationLayoutKey = null
        paginationLayoutJson = null
        mutableState.update {
            it.copy(
                currentVisualPage = null,
                totalVisualPages = null,
                paginationPhase = EpubPaginationPhase.CALCULATING,
                paginationGeneration = paginationGeneration,
            )
        }
    }

    internal suspend fun preparePagination(snapshot: EpubPaginationLayoutSnapshot): EpubPaginationRequest {
        paginationGeneration += 1
        val generation = paginationGeneration
        paginationLayoutKey = snapshot.key
        paginationLayoutJson = snapshot.json
        lastVisualHref = null
        lastVisualPageIndex = null
        lastVisualTotalPages = null

        val publicationKey = currentPublicationKey ?: "chapter:$chapterId"
        val publication = sessionRepository.get(chapterId)?.publication
        if (publication?.metadata?.layout == Layout.FIXED) {
            val fixedCounts = publication.readingOrder.associate { link ->
                link.href.toString().normalizedResourceHref() to 1
            }
            bookVisualPageCounts = fixedCounts
            val totalPages = fixedCounts.size.coerceAtLeast(1)
            val currentPage = pageFromLocator(latestLocator, publication.readingOrder, fixedCounts) ?: 1
            visualPageNumber = currentPage
            mutableState.update {
                it.copy(
                    currentVisualPage = currentPage,
                    totalVisualPages = totalPages,
                    paginationPhase = EpubPaginationPhase.READY,
                    paginationGeneration = generation,
                )
            }
            persistPaginationCache(isComplete = true)
            return EpubPaginationRequest(
                generation = generation,
                publicationKey = publicationKey,
                layoutKey = snapshot.key,
                layoutSnapshotJson = snapshot.json,
                initialPageCounts = fixedCounts,
                shouldScan = false,
            )
        }
        if (snapshot.readingMode == koharia.epub.settings.EpubLayoutPreferences.ReadingMode.SCROLL.name) {
            bookVisualPageCounts = emptyMap()
            visualPageNumber = null
            mutableState.update {
                it.copy(
                    currentVisualPage = null,
                    totalVisualPages = null,
                    paginationPhase = EpubPaginationPhase.UNAVAILABLE,
                    paginationGeneration = generation,
                )
            }
            return EpubPaginationRequest(
                generation = generation,
                publicationKey = publicationKey,
                layoutKey = snapshot.key,
                layoutSnapshotJson = snapshot.json,
                initialPageCounts = emptyMap(),
                shouldScan = false,
            )
        }

        val cache = if (isIncognito()) {
            null
        } else {
            getEpubPaginationCache.await(chapterId, publicationKey, snapshot.key)
        }
        val readingOrder = sessionRepository.get(chapterId)?.publication?.readingOrder.orEmpty()
        val cachedCounts = cache?.resourcePageCountsJson
            ?.toPageCounts()
            ?.orderedFor(readingOrder)
            .orEmpty()
        val isComplete = cache?.isComplete == true && cachedCounts.size == readingOrder.size
        val cachedLocator = cache?.currentLocatorJson
            ?.let { locatorJson -> runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull() }
        val locatorMatches = cachedLocator?.isSamePaginationLocation(latestLocator) == true

        bookVisualPageCounts = cachedCounts
        val cachedCurrentPage = cache?.currentVisualPage?.toInt()
            ?.takeIf { locatorMatches }
        val cachedTotalPages = cachedCounts.values.sum().takeIf { isComplete && it > 0 }
        val hasCachedPagePair = cachedCurrentPage != null && cachedTotalPages != null
        logcat(LogPriority.DEBUG) {
            "EPUB pagination cache result chapterId=$chapterId key=${snapshot.key.take(12)} " +
                "hit=${cache != null} complete=$isComplete resources=${cachedCounts.size}/${readingOrder.size} " +
                "locatorMatches=$locatorMatches pages=$cachedCurrentPage/$cachedTotalPages"
        }
        visualPageNumber = cachedCurrentPage.takeIf { hasCachedPagePair }
        mutableState.update {
            it.copy(
                currentVisualPage = cachedCurrentPage.takeIf { hasCachedPagePair },
                totalVisualPages = cachedTotalPages.takeIf { hasCachedPagePair },
                paginationPhase = if (hasCachedPagePair) {
                    EpubPaginationPhase.CACHED
                } else {
                    EpubPaginationPhase.CALCULATING
                },
                paginationGeneration = generation,
            )
        }

        return EpubPaginationRequest(
            generation = generation,
            publicationKey = publicationKey,
            layoutKey = snapshot.key,
            layoutSnapshotJson = snapshot.json,
            initialPageCounts = cachedCounts,
            shouldScan = !isComplete,
        )
    }

    fun updateBookPagination(
        generation: Long,
        pageCounts: Map<String, Int>,
        isComplete: Boolean,
    ) {
        if (generation != paginationGeneration) return
        val readingOrder = sessionRepository.get(chapterId)?.publication?.readingOrder.orEmpty()
        if (readingOrder.isEmpty()) return
        val orderedCounts = pageCounts.orderedFor(readingOrder)
        bookVisualPageCounts = orderedCounts
        if (!isComplete || orderedCounts.size != readingOrder.size) {
            viewModelScope.launch { persistPaginationCache(isComplete = false) }
            return
        }

        val totalPages = orderedCounts.values.sum().coerceAtLeast(1)
        val href = lastVisualHref
            ?: latestLocator?.href?.toString()?.normalizedResourceHref()
        val currentPage = lastVisualPageIndex
            ?.let { exactVisualPage(href, it, readingOrder, orderedCounts) }
        val coercedCurrentPage = currentPage?.coerceIn(1, totalPages)
        logcat(LogPriority.DEBUG) {
            "EPUB pagination scan complete chapterId=$chapterId generation=$generation " +
                "resources=${orderedCounts.size}/${readingOrder.size} pages=$coercedCurrentPage/$totalPages"
        }

        visualPageNumber = coercedCurrentPage
        mutableState.update {
            it.copy(
                currentVisualPage = coercedCurrentPage,
                totalVisualPages = totalPages.takeIf { coercedCurrentPage != null },
                paginationPhase = if (coercedCurrentPage != null) {
                    EpubPaginationPhase.READY
                } else {
                    EpubPaginationPhase.CALCULATING
                },
            )
        }

        if (latestLocator != null) {
            viewModelScope.launch {
                persistPaginationCache(isComplete = true)
                updateChapterPageProgress()
            }
        }
    }

    private fun Map<String, Int>.orderedFor(readingOrder: List<Link>): Map<String, Int> {
        val source = this
        return buildMap {
            readingOrder.forEach { link ->
                val href = link.href.toString().normalizedResourceHref()
                source.entries.firstOrNull { (candidate, count) ->
                    count > 0 && href.isSameResourceHref(candidate)
                }?.value?.let { put(href, it) }
            }
        }
    }

    private fun pageFromLocator(
        locator: Locator?,
        readingOrder: List<Link>,
        pageCounts: Map<String, Int>,
    ): Int? {
        locator ?: return null
        if (pageCounts.size != readingOrder.size) return null
        val href = locator.href.toString().normalizedResourceHref()
        val resourcePages = pageCounts.entries.firstOrNull { (candidate, _) ->
            href.isSameResourceHref(candidate)
        }?.value ?: return null
        val progression = (locator.locations.progression as? Number)?.toDouble() ?: 0.0
        var pageIndex = (progression * resourcePages).roundToInt().coerceIn(0, resourcePages - 1)
        val isRtl = runCatching {
            JSONObject(paginationLayoutJson.orEmpty()).optString("pageDirection") ==
                koharia.epub.settings.EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT.name
        }.getOrDefault(false)
        if (isRtl && pageIndex > 0) pageIndex -= 1
        return exactVisualPage(href, pageIndex, readingOrder, pageCounts)
    }

    private fun exactVisualPage(
        href: String?,
        pageIndex: Int,
        readingOrder: List<Link>,
        pageCounts: Map<String, Int>,
    ): Int? {
        if (href.isNullOrBlank() || pageCounts.size != readingOrder.size) return null

        var pagesBefore = 0
        for (link in readingOrder) {
            val resourceHref = link.href.toString().normalizedResourceHref()
            val resourcePages = pageCounts[resourceHref] ?: return null
            if (resourceHref.isSameResourceHref(href)) {
                return pagesBefore + (pageIndex + 1).coerceIn(1, resourcePages)
            }
            pagesBefore += resourcePages
        }
        return null
    }

    private fun resetVisualPagination() {
        visualPageNumber = null
        lastVisualHref = null
        lastVisualPageIndex = null
        lastVisualTotalPages = null
        bookVisualPageCounts = emptyMap()
        paginationLayoutKey = null
        paginationLayoutJson = null
    }

    fun dismissServerTimeWarning() {
        mutableState.update { it.copy(serverTimeOffsetMinutes = null) }
    }

    fun locatorAtPosition(index: Int): Locator? = publicationPositions.getOrNull(index)

    fun locatorAtProgression(progression: Double): Locator? {
        if (publicationPositions.isEmpty()) return null
        val index = (progression.coerceIn(0.0, 1.0) * (publicationPositions.size - 1))
            .roundToInt()
        return publicationPositions.getOrNull(index)
    }

    fun currentLocator(): Locator? = latestLocator

    suspend fun saveCurrentProgress() {
        if (!isIncognito()) {
            latestLocator?.let { persistLocator(it) }
        }
    }

    fun tableOfContents(): List<EpubTocEntry> {
        val session = sessionRepository.get(chapterId) ?: return emptyList()
        return flattenLinks(session.publication.tableOfContents)
    }

    fun isIncognito(): Boolean = incognitoSession

    fun restartReadTimer() {
        historyReadStartTime = if (isIncognito()) null else System.currentTimeMillis()
    }

    suspend fun updateHistory() {
        if (isIncognito()) return

        val chapterId = chapterId.takeIf { it > 0 } ?: return
        val endTime = Date()
        val sessionReadDuration = historyReadStartTime
            ?.let { startTime -> (endTime.time - startTime).coerceAtLeast(0L) }
            ?: 0L
        upsertHistory.await(
            HistoryUpdate(
                chapterId = chapterId,
                readAt = endTime,
                sessionReadDuration = sessionReadDuration,
            ),
        )
        historyReadStartTime = null
    }

    fun adjacentTocEntries(
        entries: List<EpubTocEntry>,
        currentPosition: Int,
        currentHref: String?,
    ): Pair<EpubTocEntry?, EpubTocEntry?> {
        val positionedEntries = entries
            .mapNotNull { entry ->
                entry.link.href.toString().hrefCandidates()
                    .firstNotNullOfOrNull(publicationPositionByHref::get)
                    ?.let { position -> entry to position }
            }
            .sortedBy { (_, position) -> position }

        val normalizedCurrentHref = currentHref?.normalizedNavigationHref()
        val exactCurrentIndex = positionedEntries.indexOfLast { (entry, _) ->
            normalizedCurrentHref != null &&
                entry.link.href.toString().normalizedNavigationHref() == normalizedCurrentHref
        }
        val currentSectionPosition = positionedEntries
            .asSequence()
            .map { (_, position) -> position }
            .filter { position -> position <= currentPosition }
            .maxOrNull()
        val currentIndex = exactCurrentIndex.takeIf { it >= 0 }
            ?: currentSectionPosition?.let { position ->
                positionedEntries.indexOfFirst { (_, candidate) -> candidate == position }
            }
            ?: -1
        if (currentIndex < 0) {
            return null to positionedEntries.firstOrNull()?.first
        }
        return positionedEntries.getOrNull(currentIndex - 1)?.first to
            positionedEntries.getOrNull(currentIndex + 1)?.first
    }

    fun locatorFromBookmark(bookmark: EpubBookmark): Locator? {
        return bookmark.toLocatorOrNull()
    }

    fun toggleBookmarkAtCurrentLocator() {
        if (isIncognito()) return
        val locator = latestLocator ?: return
        val readerState = state.value
        val progression = (locator.totalProgressionValue() ?: readerState.progression)
            .coerceIn(0.0, 1.0)
        val sectionTitle = locator.title?.takeIf(String::isNotBlank)
            ?: tableOfContents()
                .firstOrNull { entry ->
                    locator.href.toString().isSameResourceHref(entry.link.href.toString())
                }
                ?.link
                ?.title
                ?.takeIf(String::isNotBlank)
            ?: readerState.currentSectionTitle?.takeIf(String::isNotBlank)
            ?: readerState.chapterTitle?.takeIf(String::isNotBlank)

        val existingId = readerState.currentBookmarkId
        viewModelScope.launch {
            if (existingId != null) {
                deleteEpubBookmark.await(existingId)
            } else {
                addEpubBookmark.await(
                    EpubBookmark(
                        id = 0,
                        chapterId = chapterId,
                        mangaId = mangaId,
                        locatorJson = locator.toJSON().toString(),
                        sectionTitle = sectionTitle,
                        progression = progression,
                        note = null,
                        createdAt = Date(),
                    ),
                )
            }
            refreshBookmarks()
        }
    }

    fun deleteBookmark(id: Long) {
        if (isIncognito()) return
        viewModelScope.launch {
            deleteEpubBookmark.await(id)
            refreshBookmarks()
        }
    }

    fun updateBookmarkNote(id: Long, note: String?) {
        if (isIncognito()) return
        viewModelScope.launch {
            updateEpubBookmarkNote.await(id, note?.takeIf(String::isNotBlank))
            refreshBookmarks()
        }
    }

    fun openSearch() {
        searchIterator?.close()
        searchIterator = null
        mutableState.update {
            it.copy(
                isSearchActive = true,
                isSearchSubmitted = false,
                searchQuery = "",
                searchResults = emptyList(),
                isSearchLoading = false,
                searchErrorMessage = null,
            )
        }
    }

    fun updateSearchQuery(query: String) {
        mutableState.update {
            it.copy(
                searchQuery = query,
                isSearchSubmitted = false,
                searchResults = emptyList(),
                isSearchLoading = false,
                searchErrorMessage = null,
            )
        }
    }

    fun submitSearch() {
        val query = state.value.searchQuery.trim()
        if (query.isEmpty()) return
        mutableState.update {
            it.copy(
                searchQuery = query,
                isSearchSubmitted = true,
                searchResults = emptyList(),
                isSearchLoading = true,
                searchErrorMessage = null,
            )
        }
        searchUpdates.tryEmit(query)
    }

    fun dismissSearchResults() {
        searchIterator?.close()
        searchIterator = null
        mutableState.update {
            it.copy(
                isSearchSubmitted = false,
                searchResults = emptyList(),
                isSearchLoading = false,
                searchErrorMessage = null,
            )
        }
    }

    fun closeSearch() {
        searchIterator?.close()
        searchIterator = null
        mutableState.update {
            it.copy(
                isSearchActive = false,
                isSearchSubmitted = false,
                searchQuery = "",
                searchResults = emptyList(),
                isSearchLoading = false,
                searchErrorMessage = null,
            )
        }
    }

    fun onSessionMissing(missingChapterId: Long) {
        if (missingChapterId != chapterId || state.value.isLoading) return
        val mangaId = mangaId.takeIf { it > 0 } ?: return
        val chapterId = chapterId.takeIf { it > 0 } ?: return
        viewModelScope.launch {
            if (sessionRepository.get(chapterId) == null) {
                init(mangaId, chapterId)
            }
        }
    }

    fun releaseSession() {
        locatorPersistenceJob.cancel()
        sessionRepository.remove(chapterId)?.close()

        val locator = latestLocator
        if (locator != null && !isIncognito()) {
            sessionReleaseScope.launch {
                runCatching { persistLocator(locator) }
                    .onFailure { error ->
                        logcat(LogPriority.ERROR, error) {
                            "Failed to persist final EPUB locator for chapterId=$chapterId"
                        }
                    }
            }
        }
        publicationPositions = emptyList()
        publicationPositionByHref = emptyMap()
        resetVisualPagination()
    }

    override fun onCleared() {
        searchIterator?.close()
        searchIterator = null
        super.onCleared()
    }

    private suspend fun refreshBookmarks() {
        val chapterId = chapterId.takeIf { it > 0 } ?: return
        val bookmarks = getEpubBookmarks.await(chapterId)
        val currentBookmarkId = findBookmarkForLocator(bookmarks, latestLocator)?.id
        mutableState.update {
            it.copy(
                bookmarks = bookmarks,
                currentBookmarkId = currentBookmarkId,
            )
        }
    }

    private suspend fun performSearch(query: String) {
        searchIterator?.close()
        searchIterator = null
        if (query.isBlank()) {
            mutableState.update {
                it.copy(
                    searchResults = emptyList(),
                    isSearchLoading = false,
                    searchErrorMessage = null,
                )
            }
            return
        }

        val session = sessionRepository.get(chapterId)
        if (session == null || !session.publication.isSearchable) {
            mutableState.update {
                it.copy(
                    searchResults = emptyList(),
                    isSearchLoading = false,
                    searchErrorMessage = application.stringResource(MR.strings.epub_reader_search_not_supported),
                )
            }
            return
        }

        mutableState.update {
            it.copy(
                isSearchLoading = true,
                searchErrorMessage = null,
            )
        }

        runCatching {
            val iterator = session.publication.search(query)
                ?: error(application.stringResource(MR.strings.epub_reader_search_not_supported))
            searchIterator = iterator
            val results = buildList {
                while (true) {
                    val collection = iterator.next().getOrElse { error ->
                        throw IllegalStateException(error.message)
                    } ?: break
                    collection.locators.forEach { locator ->
                        add(
                            EpubSearchResult(
                                locator = locator,
                                title = locator.title,
                                before = locator.text.before,
                                highlight = locator.text.highlight,
                                after = locator.text.after,
                            ),
                        )
                    }
                }
            }
            results
        }.onSuccess { results ->
            mutableState.update {
                it.copy(
                    searchResults = results,
                    isSearchLoading = false,
                    searchErrorMessage = null,
                )
            }
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) {
                "EPUB search failed chapterId=$chapterId query=$query"
            }
            mutableState.update {
                it.copy(
                    searchResults = emptyList(),
                    isSearchLoading = false,
                    searchErrorMessage = error.message
                        ?: application.stringResource(MR.strings.epub_reader_search_error),
                )
            }
        }
    }

    private fun restoreLocator(): Locator? =
        locatorJson?.let {
            runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull()
        }

    private suspend fun syncPersistedProgress(
        localProgress: EpubProgress,
        locator: Locator,
    ) {
        val sourceId = currentSourceId.takeIf { it > 0 } ?: return
        val bookUrl = localProgress.bookUrl ?: currentBookUrl ?: return
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

    private suspend fun persistLocator(locator: Locator) = progressPersistenceMutex.withLock {
        val chapterId = chapterId.takeIf { it > 0 } ?: return@withLock
        mangaId.takeIf { it > 0 } ?: return@withLock
        val now = Date()
        val progress = buildProgress(
            locator = locator,
            updatedAt = now,
            lastSyncedAt = currentProgress?.lastSyncedAt,
        )
        upsertEpubProgress.await(progress)
        currentProgress = progress
        persistPaginationCache(
            isComplete = mutableState.value.paginationPhase in setOf(
                EpubPaginationPhase.CACHED,
                EpubPaginationPhase.READY,
            ),
        )
        updateChapterPageProgress()
        markChapterCompletedIfNeeded(locator)

        val bookUrl = progress.bookUrl
        if (bookUrl != null && epubReaderPreferences.syncProgressionToKomga.get()) {
            val sourceId = currentSourceId.takeIf { it > 0 } ?: return@withLock
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

    private suspend fun updateChapterPageProgress() {
        if (currentChapterRead) return
        val currentPage = mutableState.value.currentVisualPage ?: return
        updateChapter.await(
            ChapterUpdate(
                id = chapterId,
                lastPageRead = (currentPage - 1).toLong(),
            ),
        )
    }

    private suspend fun markChapterCompletedIfNeeded(locator: Locator) {
        if (completionMarkedThisSession || currentChapterRead) return

        val totalProgression = (locator.locations.totalProgression as? Number)?.toDouble() ?: return
        val threshold = epubReaderPreferences.completionThresholdPercent.get().coerceIn(0, 100) / 100.0
        if (totalProgression < threshold) return

        updateChapter.await(
            ChapterUpdate(
                id = chapterId,
                read = true,
                lastPageRead = 0,
            ),
        )
        currentChapterRead = true
        completionMarkedThisSession = true
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
            progression = locator.totalProgressionValue()
                ?: locator.positionIn(publicationPositions).toProgression(publicationPositions.size),
            positionIndex = locator.positionIndex(),
            updatedAt = updatedAt,
            lastSyncedAt = lastSyncedAt,
        )
    }

    private suspend fun persistPaginationCache(isComplete: Boolean) {
        if (isIncognito()) return
        val publicationKey = currentPublicationKey ?: return
        val layoutKey = paginationLayoutKey ?: return
        val layoutJson = paginationLayoutJson ?: return
        if (bookVisualPageCounts.isEmpty()) return
        val state = mutableState.value
        upsertEpubPaginationCache.await(
            EpubPaginationCache(
                chapterId = chapterId,
                publicationKey = publicationKey,
                layoutKey = layoutKey,
                layoutSnapshotJson = layoutJson,
                resourcePageCountsJson = bookVisualPageCounts.toPageCountsJson(),
                currentLocatorJson = latestLocator?.toJSON()?.toString(),
                currentVisualPage = state.currentVisualPage?.toLong(),
                totalVisualPages = state.totalVisualPages?.toLong(),
                isComplete = isComplete,
                measuredResourceCount = bookVisualPageCounts.size.toLong(),
                updatedAt = Date(),
            ),
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

    private fun findBookmarkForLocator(
        bookmarks: List<EpubBookmark>,
        locator: Locator?,
    ): EpubBookmark? {
        locator ?: return null
        return bookmarks.firstOrNull { bookmark ->
            val bookmarkLocator = bookmark.toLocatorOrNull() ?: return@firstOrNull false
            bookmarkLocator.href.toString() == locator.href.toString() &&
                bookmarkLocator.positionIndex() == locator.positionIndex() &&
                bookmarkLocator.totalProgressionValue().isSameBookmarkProgression(locator.totalProgressionValue())
        }
    }

    private fun EpubBookmark.toLocatorOrNull(): Locator? {
        return runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull()
    }

    private fun EpubProgress.toLocatorOrNull(): Locator? {
        return runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull()
    }

    private fun Locator.progressionPercent(): Int? {
        val progression = totalProgressionValue()
            ?: positionIn(publicationPositions).toProgression(publicationPositions.size)
        return (progression * 100).roundToInt().coerceIn(0, 100)
    }

    private fun Locator.totalProgressionValue(): Double? {
        return (locations.totalProgression as? Number)?.toDouble()
    }

    private fun Locator.positionIndex(): Long? {
        return (locations.position as? Number)?.toLong()
    }

    private fun Locator.navigationHref(): String {
        val rawHref = href.toString()
        if ('#' in rawHref) return rawHref.normalizedNavigationHref()
        val fragment = locations.fragments.firstOrNull()
            ?.removePrefix("#")
            ?.takeIf(String::isNotBlank)
        val resourceHref = rawHref.normalizedResourceHref()
        return fragment?.let { "$resourceHref#$it" } ?: resourceHref
    }

    private fun Locator.resourceProgressionValue(): Double? {
        return (locations.progression as? Number)?.toDouble()
    }

    private fun Locator.isSamePaginationLocation(other: Locator?): Boolean {
        other ?: return false
        if (!href.toString().isSameResourceHref(other.href.toString())) return false

        val progression = resourceProgressionValue()
        val otherProgression = other.resourceProgressionValue()
        if (progression != null && otherProgression != null) {
            return abs(progression - otherProgression) < PAGINATION_LOCATOR_PROGRESSION_TOLERANCE
        }

        val position = positionIndex()
        val otherPosition = other.positionIndex()
        return position != null && otherPosition != null && position == otherPosition
    }

    private fun Locator?.positionIn(positions: List<Locator>): Int {
        val totalPositions = positions.size.coerceAtLeast(1)
        val explicitPosition = this?.positionIndex()?.toInt()
        if (explicitPosition != null) return explicitPosition.coerceIn(1, totalPositions)

        val totalProgression = this?.totalProgressionValue() ?: return 1
        return (totalProgression * (totalPositions - 1)).roundToLong().toInt()
            .plus(1)
            .coerceIn(1, totalPositions)
    }

    private fun Int.toProgression(totalPositions: Int): Double {
        if (totalPositions <= 1) return 0.0
        return ((this - 1).toDouble() / (totalPositions - 1)).coerceIn(0.0, 1.0)
    }

    private fun Date.serverTimeOffsetMinutes(): Long? {
        val offsetMillis = time - System.currentTimeMillis()
        if (abs(offsetMillis) < SERVER_TIME_WARNING_THRESHOLD_MS) return null
        return (abs(offsetMillis) / 60_000.0).roundToLong().coerceAtLeast(1L)
    }

    private fun Locator?.debugProgress(): String {
        if (this == null) return "none"
        val resourceProgression = (locations.progression as? Number)?.toDouble()
        return "href=$href resource=$resourceProgression total=${totalProgressionValue()} position=${positionIndex()}"
    }

    private fun String.isSameResourceHref(other: String): Boolean {
        val first = normalizedResourceHref()
        val second = other.normalizedResourceHref()
        if (first.isBlank() || second.isBlank()) return false
        return first == second || first.endsWith("/$second") || second.endsWith("/$first")
    }

    private fun String.normalizedResourceHref(): String =
        substringBefore('#')
            .substringBefore('?')
            .trimStart('/')

    private fun String.normalizedNavigationHref(): String {
        val resourceHref = normalizedResourceHref()
        val fragment = substringAfter('#', "")
            .removePrefix("#")
            .takeIf(String::isNotBlank)
        return fragment?.let { "$resourceHref#$it" } ?: resourceHref
    }

    private fun String.hrefCandidates(): List<String> {
        val normalized = normalizedResourceHref()
        if (normalized.isBlank()) return emptyList()
        val segments = normalized.split('/').filter(String::isNotBlank)
        return segments.indices.map { index -> segments.drop(index).joinToString("/") }
    }

    private fun Double?.isSameBookmarkProgression(other: Double?): Boolean {
        if (this == null || other == null) return this == other
        return abs(this - other) < 0.0001
    }
}

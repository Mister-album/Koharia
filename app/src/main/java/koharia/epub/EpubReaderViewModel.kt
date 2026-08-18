package koharia.epub

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.saver.EncodedFormat
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.ui.reader.SaveImageNotifier
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.storage.DiskUtil
import koharia.connection.ConnectionEpubProgressAdapter
import koharia.connection.ConnectionPublicationAdapter
import koharia.connection.ConnectionPublicationMetadata
import koharia.connection.ConnectionScopedPreferenceStoreFactory
import koharia.connection.ConnectionSource
import koharia.connection.RemoteEpubProgression
import koharia.connection.isConnectionLibraryEntry
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
import koharia.epub.cache.EpubCacheManager
import koharia.epub.cache.EpubCachePolicy
import koharia.epub.cache.EpubCachePreferences
import koharia.epub.locator.toPersistentLocator
import koharia.epub.model.EpubOpenRequest
import koharia.epub.model.EpubSearchResult
import koharia.epub.model.EpubTocEntry
import koharia.epub.model.RemotePublicationRef
import koharia.epub.progress.EpubRemoteProgressDecision
import koharia.epub.progress.EpubRemoteProgressPolicy
import koharia.epub.service.EpubPublicationResolver
import koharia.epub.session.EpubReaderSession
import koharia.epub.session.EpubReaderSessionRepository
import koharia.epub.settings.EpubReaderPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.isSearchable
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.SessionPreferenceStore
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
import java.io.File
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
    private val epubCacheManager: EpubCacheManager = Injekt.get(),
    private val epubCachePreferences: EpubCachePreferences = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val getEpubBookmarks: GetEpubBookmarks = Injekt.get(),
    private val addEpubBookmark: AddEpubBookmark = Injekt.get(),
    private val deleteEpubBookmark: DeleteEpubBookmark = Injekt.get(),
    private val updateEpubBookmarkNote: UpdateEpubBookmarkNote = Injekt.get(),
    private val imageSaver: ImageSaver = Injekt.get(),
    globalEpubReaderPreferences: EpubReaderPreferences = Injekt.get(),
    globalBasePreferences: BasePreferences = Injekt.get(),
    private val scopedPreferenceStoreFactory: ConnectionScopedPreferenceStoreFactory = Injekt.get(),
) : ViewModel() {

    private var epubReaderPreferences: EpubReaderPreferences =
        scopedPreferenceStoreFactory.epubReaderPreferencesForSavedSource(savedState) ?: globalEpubReaderPreferences
    private var basePreferences: BasePreferences =
        scopedPreferenceStoreFactory.basePreferencesForSavedSource(savedState) ?: globalBasePreferences
    private var transientReaderSettingsStore: PreferenceStore? = null
    private var publisherStylesOverride: Boolean? = null

    private companion object {
        val sessionReleaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        const val COMPLETE_CACHE_IDLE_DELAY_MS = 3_000L
        const val PAGINATION_CACHE_WRITE_DEBOUNCE_MS = 250L
    }

    private val mutableState = MutableStateFlow(EpubReaderUiState())
    val state = mutableState.asStateFlow()
    private val mutableImageState = MutableStateFlow(EpubImageUiState())
    internal val imageState = mutableImageState.asStateFlow()
    private val mutableFootnoteState = MutableStateFlow<EpubFootnoteUiState?>(null)
    internal val footnoteState = mutableFootnoteState.asStateFlow()
    private val mutableImageEvents = MutableSharedFlow<EpubImageEvent>(extraBufferCapacity = 1)
    internal val imageEvents = mutableImageEvents.asSharedFlow()
    private val imageRequestTracker = EpubImageRequestTracker()
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
    private var currentProviderId: String? = null
    private var currentEpubProgressAdapter: ConnectionEpubProgressAdapter? = null
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
    private var isRtlLayout = false
    private var currentPublicationKey: String? = null
    private var leasedCacheFile: File? = null
    private var leasedPublicationKey: String? = null
    private var completeCacheStarted = false
    private var completeCacheJob: Job? = null
    private var paginationPersistJob: Job? = null
    private var positionsRefreshStarted = false
    private var lastPrefetchedHref: String? = null
    private var remoteProgressChecked = false
    private var remoteProgressWriteAllowed = false
    private var remoteProgressWriteBaseline: Locator? = null
    private var localProgressUpdatedAtSessionOpen: Long? = null
    private var initiallyAcceptedRemoteLocator: Locator? = null
    private var initiallyAcceptedRemoteModifiedAt: Date? = null
    private var layoutChangeRevision = 0L
    private var preserveLocalProgressAfterLayoutChange = false
    private var currentChapter: tachiyomi.domain.chapter.model.Chapter? = null
    private var currentChapterUrl: String? = null
    private var currentChapterRead = false
    private var currentChapterBookmark = false
    private var historyReadStartTime: Long? = null
    private var completionMarkedThisSession = false
    private var searchIterator: SearchIterator? = null
    private var imageLoadJob: Job? = null
    private var footnoteLoadJob: Job? = null
    private var incognitoSession = basePreferences.incognitoMode.get()
    private val locatorPersistenceJob = viewModelScope.launch {
        locatorUpdates
            .debounce(750L)
            .collect { locator -> persistLocator(locator) }
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
            ?: SessionPreferenceStore(backingStore, persistChanges).also { transientReaderSettingsStore = it }
    }

    internal fun setPersistReaderSettingsChanges(enabled: Boolean) {
        (transientReaderSettingsStore as? SessionPreferenceStore)?.setPersistChanges(enabled)
    }

    internal fun setPublisherStylesOverride(enabled: Boolean?) {
        publisherStylesOverride = enabled
    }

    suspend fun init(
        mangaId: Long,
        chapterId: Long,
        preserveLocalProgressAfterLayoutChange: Boolean = false,
    ): Result<Unit> {
        completeCacheJob?.cancel()
        completeCacheJob = null
        paginationPersistJob?.cancel()
        paginationPersistJob = null
        releaseCacheLeases()
        completeCacheStarted = false
        positionsRefreshStarted = false
        lastPrefetchedHref = null
        initiallyAcceptedRemoteLocator = null
        initiallyAcceptedRemoteModifiedAt = null
        layoutChangeRevision += 1
        this.preserveLocalProgressAfterLayoutChange = preserveLocalProgressAfterLayoutChange
        if (!preserveLocalProgressAfterLayoutChange) {
            remoteProgressChecked = false
            remoteProgressWriteAllowed = false
            remoteProgressWriteBaseline = null
            localProgressUpdatedAtSessionOpen = null
        }
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
                currentChapter = chapter
                val source = sourceManager.get(manga.source) as? ConnectionSource
                    ?: error(application.stringResource(MR.strings.source_unsupported))
                val publicationAdapter = source as? ConnectionPublicationAdapter

                savedState["source_id"] = source.id
                currentSourceId = source.id
                currentProviderId = source.providerId
                currentEpubProgressAdapter = source as? ConnectionEpubProgressAdapter
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

                val hasLauncherResolution =
                    savedState.get<Long>("epub_resolution_chapter") == chapter.id &&
                        savedState.get<Long>("epub_resolution_source") == source.id
                val resolvedLocalUri = savedState.get<String>("epub_resolution_local_uri")
                val resolvedRemoteBookUrl = savedState.get<String>("epub_resolution_remote_url")
                val resolvedOpenSource = savedState.get<String>("epub_resolution_open_source")
                    ?.let { value -> runCatching { EpubOpenRequest.OpenSource.valueOf(value) }.getOrNull() }
                val resolvedPublicationKey = savedState.get<String>("epub_resolution_publication_key")
                val resolvedCompleteCache =
                    savedState.get<Boolean>("epub_resolution_complete_cache") == true

                val downloadedFile = if (hasLauncherResolution) {
                    null
                } else {
                    downloadProvider.findChapterDir(
                        chapterName = chapter.name,
                        chapterScanlator = chapter.scanlator,
                        chapterUrl = chapter.url,
                        mangaTitle = manga.title,
                        source = source,
                    )
                }
                val downloadedUri = downloadedFile
                    ?.takeIf { it.extension.equals("epub", ignoreCase = true) }
                    ?.uri
                    ?.toString()

                val publicationMetadata = when {
                    publicationAdapter != null -> publicationAdapter.resolvePublication(
                        chapter = chapter,
                        allowRemoteLookup = !hasLauncherResolution,
                    )
                    hasLauncherResolution && resolvedLocalUri != null -> ConnectionPublicationMetadata(
                        remoteResourceId = resolvedRemoteBookUrl,
                        publicationKey = resolvedPublicationKey ?: "local:$resolvedLocalUri",
                        isPageCompatible = savedState.get<Boolean>("epub_resolution_divina") == true,
                        fileName = savedState.get<String>("epub_resolution_file_name"),
                        sizeBytes = savedState.get<Long>("epub_resolution_file_size")?.takeIf { it > 0L },
                    )
                    else -> error(application.stringResource(MR.strings.source_unsupported))
                }
                val cachedBookFile = epubCacheManager.completeBookFile(source.id, publicationMetadata.publicationKey)
                val cachedBookUri = cachedBookFile?.toURI()?.toString()
                val reusableResolvedLocalUri = resolvedLocalUri
                    .takeUnless { resolvedCompleteCache && cachedBookFile == null }
                val preferredLocalUri = reusableResolvedLocalUri ?: downloadedUri ?: cachedBookUri
                val remoteBookUrl = when {
                    hasLauncherResolution -> resolvedRemoteBookUrl
                    else -> publicationMetadata.remoteResourceId
                }
                val canOpenAsPages = if (hasLauncherResolution) {
                    savedState.get<Boolean>("epub_resolution_divina") == true
                } else {
                    publicationMetadata.isPageCompatible
                }

                check(preferredLocalUri != null || remoteBookUrl != null) {
                    application.stringResource(MR.strings.epub_reader_unsupported_book)
                }

                val primarySource = when {
                    hasLauncherResolution && resolvedOpenSource == EpubOpenRequest.OpenSource.LOCAL &&
                        preferredLocalUri != null -> EpubOpenRequest.OpenSource.LOCAL
                    hasLauncherResolution && resolvedOpenSource == EpubOpenRequest.OpenSource.REMOTE &&
                        remoteBookUrl != null -> EpubOpenRequest.OpenSource.REMOTE
                    preferredLocalUri != null ->
                        EpubOpenRequest.OpenSource.LOCAL
                    remoteBookUrl != null ->
                        EpubOpenRequest.OpenSource.REMOTE
                    else ->
                        EpubOpenRequest.OpenSource.LOCAL
                }
                val localUri = preferredLocalUri
                val bookFileName = savedState.get<String>("epub_resolution_file_name")
                    ?.takeIf { hasLauncherResolution }
                    ?: downloadedFile?.name
                    ?: cachedBookFile?.name
                    ?: publicationMetadata.fileName
                val bookSizeBytes = savedState.get<Long>("epub_resolution_file_size")
                    ?.takeIf { hasLauncherResolution && it > 0L }
                    ?: downloadedFile?.length()?.takeIf { size -> size > 0L }
                    ?: cachedBookFile?.length()?.takeIf { size -> size > 0L }
                    ?: publicationMetadata.sizeBytes
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
                    hasLauncherResolution && !resolvedPublicationKey.isNullOrBlank() -> resolvedPublicationKey
                    primarySource == EpubOpenRequest.OpenSource.LOCAL && downloadedFile != null ->
                        "local:$localUri:${downloadedFile.lastModified()}:${downloadedFile.length()}"
                    primarySource == EpubOpenRequest.OpenSource.LOCAL && cachedBookFile != null ->
                        publicationMetadata.publicationKey
                    primarySource == EpubOpenRequest.OpenSource.LOCAL ->
                        "local:$localUri"
                    else -> publicationMetadata.publicationKey
                }
                epubCacheManager.acquirePublication(source.id, checkNotNull(currentPublicationKey))
                leasedPublicationKey = currentPublicationKey
                cachedBookFile?.takeIf {
                    primarySource == EpubOpenRequest.OpenSource.LOCAL && localUri == cachedBookUri
                }?.let {
                    epubCacheManager.acquire(it)
                    leasedCacheFile = it
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
                val remoteCache = if (incognito || remoteBookUrl == null ||
                    !epubReaderPreferences.syncRemoteProgression.get()
                ) {
                    null
                } else {
                    runCatching { currentEpubProgressAdapter?.getCachedEpubProgress(chapter.id) }
                        .onFailure { error ->
                            logcat(LogPriority.WARN, error) {
                                "Failed to load cached remote EPUB progression for chapterId=${chapter.id}"
                            }
                        }
                        .getOrNull()
                }
                localProgressUpdatedAtSessionOpen = localProgress?.updatedAt?.time
                val remoteProgress = remoteCache?.let { cache ->
                    val locator = cache.locatorJson
                        ?.let { json -> runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull() }
                    val modifiedAt = cache.modifiedAt
                    if (locator != null && modifiedAt != null) {
                        RemoteEpubProgression(locator, modifiedAt)
                    } else {
                        null
                    }
                }
                val serverTimeOffsetMinutes = remoteCache?.serverDate?.serverTimeOffsetMinutes()
                val persistedLocator = localProgress?.toLocatorOrNull()
                val savedStateLocator = restoreLocator()
                val deferCachedRemoteSelection =
                    epubReaderPreferences.correctRemoteServerTimestamps.get() &&
                        persistedLocator != null &&
                        remoteProgress != null
                val initialRemoteProgress = remoteProgress.takeUnless { deferCachedRemoteSelection }
                val initialLocator = savedStateLocator
                    ?: if (preserveLocalProgressAfterLayoutChange) {
                        persistedLocator
                    } else {
                        chooseMoreRecentLocator(localProgress, initialRemoteProgress)
                    }
                val acceptedRemoteInitially = savedStateLocator == null &&
                    !preserveLocalProgressAfterLayoutChange &&
                    initialRemoteProgress != null &&
                    (
                        localProgress == null ||
                            initialRemoteProgress.modifiedAt.time > localProgress.updatedAt.time ||
                            persistedLocator == null
                        )
                if (acceptedRemoteInitially) {
                    initiallyAcceptedRemoteLocator = initialRemoteProgress.locator
                    initiallyAcceptedRemoteModifiedAt = initialRemoteProgress.modifiedAt
                }

                logcat(LogPriority.DEBUG) {
                    "EPUB progress restore chapterId=${chapter.id} " +
                        "savedState=${savedStateLocator.debugProgress()} " +
                        "local=${localProgress?.toLocatorOrNull().debugProgress()} " +
                        "localUpdated=${localProgress?.updatedAt?.time} " +
                        "remote=${remoteProgress?.locator.debugProgress()} " +
                        "remoteUpdated=${remoteProgress?.modifiedAt?.time} " +
                        "deferredRemoteTimestampCheck=$deferCachedRemoteSelection " +
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
                        remotePublication = remoteBookUrl?.let { resourceId ->
                            RemotePublicationRef(checkNotNull(currentProviderId), resourceId)
                        },
                        localUri = localUri,
                        openSource = primarySource,
                        publisherStylesOverride = publisherStylesOverride,
                        publicationKey = checkNotNull(currentPublicationKey),
                        persistCache = !incognito,
                    ),
                    initialLocator = initialLocator,
                )
                sessionRepository.put(session)
                applyPublicationPositions(session.positionsController.currentPositions())
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

                if (!incognito && !preserveLocalProgressAfterLayoutChange && !deferCachedRemoteSelection) {
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

    suspend fun resolveDetailsRoute(): DetailsRoute? {
        val fallbackMangaId = mangaId.takeIf { it > 0 } ?: return null
        val sourceId = currentSourceId.takeIf { it > 0 }
        val chapterUrl = currentChapterUrl?.substringBefore('#')?.removeSuffix("/")
        val matchedBook = if (sourceId != null) {
            chapterUrl?.let { getMangaByUrlAndSourceId.await(it, sourceId) }
        } else {
            null
        }
        val detailsManga = matchedBook ?: getManga.await(fallbackMangaId) ?: return null
        return DetailsRoute(
            mangaId = detailsManga.id,
            sourceId = detailsManga.source,
            mangaUrl = detailsManga.url,
        )
    }

    data class DetailsRoute(
        val mangaId: Long,
        val sourceId: Long,
        val mangaUrl: String,
    )

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    internal fun showFootnote(
        link: Link,
        contentHtml: String,
        anchorXFraction: Float?,
        anchorYFraction: Float?,
    ) {
        closeImagePreview()
        footnoteLoadJob?.cancel()
        val href = link.href.toString()
        mutableFootnoteState.value = EpubFootnoteUiState(
            href = href,
            contentHtml = contentHtml,
            anchorXFraction = anchorXFraction,
            anchorYFraction = anchorYFraction,
        )
        showMenus(false)
        footnoteLoadJob = viewModelScope.launch {
            val images = try {
                withIOContext {
                    val publication = sessionRepository.get(chapterId)?.publication
                        ?: return@withIOContext emptyMap()
                    loadEpubFootnoteImages(publication, href, contentHtml)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logcat(LogPriority.WARN, error) { "Failed to load EPUB footnote images" }
                emptyMap()
            }
            mutableFootnoteState.update { state ->
                if (state?.href == href && state.contentHtml == contentHtml) {
                    state.copy(images = images)
                } else {
                    state
                }
            }
        }
    }

    internal fun dismissFootnote() {
        footnoteLoadJob?.cancel()
        footnoteLoadJob = null
        mutableFootnoteState.value = null
    }

    internal fun onImageInteraction(
        reference: EpubImageReference,
        interaction: EpubImageInteraction,
    ) {
        imageLoadJob?.cancel()
        imageRequestTracker.invalidate()
        mutableImageState.value = EpubImageUiState(
            reference = reference,
            previewVisible = interaction == EpubImageInteraction.PREVIEW,
            actionsVisible = interaction == EpubImageInteraction.ACTIONS,
        )
        showMenus(false)
        if (interaction == EpubImageInteraction.PREVIEW) {
            loadSelectedImageForPreview()
        }
    }

    internal fun showImageActions() {
        mutableImageState.update { state ->
            if (state.reference == null) state else state.copy(actionsVisible = true)
        }
    }

    internal fun dismissImageActions() {
        if (!mutableImageState.value.previewVisible) {
            imageLoadJob?.cancel()
            imageRequestTracker.invalidate()
        }
        mutableImageState.update { state ->
            if (state.previewVisible) {
                state.copy(actionsVisible = false, isLoading = false)
            } else {
                EpubImageUiState()
            }
        }
    }

    internal fun closeImagePreview() {
        imageLoadJob?.cancel()
        imageLoadJob = null
        imageRequestTracker.invalidate()
        mutableImageState.value = EpubImageUiState()
    }

    internal fun loadSelectedImageForPreview() {
        mutableImageState.update { state ->
            state.copy(previewVisible = true, actionsVisible = false, errorMessage = null)
        }
        withSelectedImage { }
    }

    internal fun retrySelectedImage() {
        withSelectedImage(forceReload = true) { }
    }

    internal fun saveSelectedImage(folderPerManga: Boolean) {
        withSelectedImage { content ->
            mutableImageState.update { it.copy(isLoading = true) }
            val notifier = SaveImageNotifier(application).apply { onClear() }
            runCatching {
                withIOContext {
                    val relativePath = if (folderPerManga) {
                        DiskUtil.buildValidFilename(state.value.mangaTitle.orEmpty())
                    } else {
                        ""
                    }
                    imageSaver.save(
                        image = content.toImage(
                            location = Location.Pictures.create(relativePath),
                        ),
                    )
                }
            }.onSuccess { uri ->
                notifier.onComplete(uri)
                finishImageAction()
                mutableImageEvents.emit(EpubImageEvent.Saved(uri))
            }.onFailure { error ->
                notifier.onError(error.message)
                reportImageActionError(error)
            }
        }
    }

    internal fun shareSelectedImage(copyToClipboard: Boolean) {
        withSelectedImage { content ->
            mutableImageState.update { it.copy(isLoading = true) }
            runCatching {
                withIOContext {
                    Location.EpubShareCache.directory(application)
                        .listFiles()
                        ?.filter(File::isFile)
                        ?.forEach { file -> file.delete() }
                    val image = content.toImage(location = Location.EpubShareCache)
                    imageSaver.save(image)
                }
            }.onSuccess { uri ->
                finishImageAction()
                mutableImageEvents.emit(
                    if (copyToClipboard) {
                        EpubImageEvent.Copy(uri)
                    } else {
                        EpubImageEvent.Share(uri, content.mimeType)
                    },
                )
            }.onFailure { error -> reportImageActionError(error) }
        }
    }

    internal fun setSelectedImageAsCover() {
        withSelectedImage { content ->
            mutableImageState.update { it.copy(isLoading = true) }
            val manga = getManga.await(mangaId)
            if (manga == null || !manga.isConnectionLibraryEntry(sourceManager)) {
                finishImageAction()
                mutableImageEvents.emit(
                    EpubImageEvent.Error(
                        application.stringResource(MR.strings.notification_first_add_to_library),
                    ),
                )
                return@withSelectedImage
            }
            val result = runCatching {
                withIOContext {
                    content.bytes.toByteArray().inputStream().use {
                        manga.editCover(it, sourceManager = sourceManager)
                    }
                }
            }
            result.onSuccess {
                finishImageAction()
                mutableImageEvents.emit(EpubImageEvent.CoverUpdated)
            }.onFailure { error ->
                logcat(LogPriority.ERROR, error) { "Failed to update EPUB series cover" }
                mutableImageState.update { it.copy(isLoading = false) }
                mutableImageEvents.emit(
                    EpubImageEvent.Error(
                        application.stringResource(MR.strings.notification_cover_update_failed),
                    ),
                )
            }
        }
    }

    private fun withSelectedImage(
        forceReload: Boolean = false,
        action: suspend (EpubImageContent) -> Unit,
    ) {
        val reference = mutableImageState.value.reference ?: return
        imageLoadJob?.cancel()
        val request = imageRequestTracker.next()
        imageLoadJob = viewModelScope.launch {
            mutableImageState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = runCatching {
                val cached = mutableImageState.value.content
                    ?.takeIf { !forceReload && it.reference == reference }
                cached ?: withIOContext {
                    val publication = sessionRepository.get(chapterId)?.publication
                        ?: error("EPUB publication session is unavailable")
                    loadEpubImageContent(publication, reference)
                }
            }
            if (!imageRequestTracker.isCurrent(request) || mutableImageState.value.reference != reference) {
                return@launch
            }
            result.onSuccess { content ->
                mutableImageState.update { it.copy(content = content, isLoading = false, errorMessage = null) }
                action(content)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                logcat(LogPriority.WARN, error) { "Failed to load EPUB image" }
                val showActionError = mutableImageState.value.actionsVisible &&
                    !mutableImageState.value.previewVisible
                mutableImageState.update {
                    it.copy(
                        content = null,
                        isLoading = false,
                        errorMessage = application.stringResource(MR.strings.epub_reader_image_load_failed),
                    )
                }
                if (showActionError) {
                    mutableImageEvents.emit(
                        EpubImageEvent.Error(application.stringResource(MR.strings.epub_reader_image_load_failed)),
                    )
                }
            }
        }
    }

    private fun finishImageAction() {
        mutableImageState.update { state ->
            if (state.previewVisible) {
                state.copy(actionsVisible = false, isLoading = false)
            } else {
                EpubImageUiState()
            }
        }
    }

    private suspend fun reportImageActionError(error: Throwable) {
        logcat(LogPriority.ERROR, error) { "Failed to export EPUB image" }
        mutableImageState.update { it.copy(isLoading = false) }
        mutableImageEvents.emit(
            EpubImageEvent.Error(application.stringResource(MR.strings.epub_reader_image_action_failed)),
        )
    }

    private fun EpubImageContent.toImage(location: Location): Image.Page {
        return Image.Page(
            inputStream = { bytes.toByteArray().inputStream() },
            name = buildEpubImageBaseName(
                seriesTitle = state.value.mangaTitle,
                bookTitle = state.value.chapterTitle,
                originalFileName = originalFileName,
                extension = extension,
            ),
            location = location,
            encodedFormat = EncodedFormat(mimeType, extension).takeIf { isSvg },
        )
    }

    fun updateLocator(locator: Locator) {
        val session = sessionRepository.get(chapterId)
        val mappedLocator = session
            ?.publication
            ?.toPersistentLocator(locator)
            ?: locator
        val persistentLocator = if (session?.positionsController?.hasAuthoritativePositions == false) {
            mappedLocator.preserveProgressMetricsFrom(latestLocator)
        } else {
            mappedLocator
        }
        if (persistentLocator !== mappedLocator) {
            logcat(LogPriority.DEBUG) {
                "Preserved EPUB progress while positions are provisional chapterId=$chapterId " +
                    "href=${persistentLocator.href} total=${persistentLocator.totalProgressionValue()} " +
                    "position=${persistentLocator.positionIndex()}"
            }
        }
        val visualPagePair = visualPagePairForLocator(persistentLocator)
        visualPagePair?.first?.let { visualPageNumber = it }
        latestLocator = persistentLocator
        val newLocatorJson = persistentLocator.toJSON().toString()
        locatorJson = newLocatorJson
        logcat(LogPriority.DEBUG) {
            "EPUB locator update chapterId=$chapterId navigatorHref=${locator.href} " +
                "${persistentLocator.debugProgress()} visual=${visualPagePair?.first}/${visualPagePair?.second}"
        }
        mutableState.update {
            it.copy(
                currentSectionTitle = persistentLocator.title ?: it.currentSectionTitle,
                currentHref = persistentLocator.navigationHref(),
                progression = persistentLocator.totalProgressionValue()
                    ?: persistentLocator.positionIn(publicationPositions).toProgression(publicationPositions.size),
                progressionPercent = persistentLocator.progressionPercent(),
                currentPosition = persistentLocator.positionIn(publicationPositions),
                currentVisualPage = visualPagePair?.first ?: it.currentVisualPage,
                totalVisualPages = visualPagePair?.second ?: it.totalVisualPages,
                paginationPhase = if (visualPagePair != null) {
                    EpubPaginationPhase.READY
                } else {
                    it.paginationPhase
                },
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

        val readingOrder = paginationReadingOrder()
        val exactPage = exactVisualPage(href, pageIndex, readingOrder, bookVisualPageCounts)
        val exactTotalPages = bookVisualPageCounts.values.sum()
            .takeIf { bookVisualPageCounts.size == readingOrder.size && it > 0 }
        if (exactPage != null && exactTotalPages != null) {
            val currentPage = exactPage.coerceIn(1, exactTotalPages)
            visualPageNumber = currentPage
            val previousState = mutableState.value
            if (previousState.currentVisualPage != currentPage ||
                previousState.totalVisualPages != exactTotalPages ||
                previousState.paginationPhase != EpubPaginationPhase.READY
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

    fun onFirstContentDisplayed() {
        refreshRemoteProgressAfterDisplay()
        refreshPositionsAfterDisplay()
        prefetchNextResourceIfNeeded()
        if (completeCacheStarted || isIncognito() || state.value.isUsingLocalFile) return
        if (!epubCachePreferences.cacheWholeBook.get()) return
        val source = sourceManager.get(currentSourceId) ?: return
        val bookUrl = currentBookUrl ?: return
        val publicationKey = currentPublicationKey ?: return
        val requestedChapterId = chapterId
        completeCacheStarted = true
        completeCacheJob = viewModelScope.launch {
            var leasedFile: File? = null
            var pendingSession: EpubReaderSession? = null
            try {
                // Let the first viewport settle before starting a full-book transfer. This keeps
                // the cache warm-up off the critical path when a reader immediately turns pages.
                delay(COMPLETE_CACHE_IDLE_DELAY_MS)
                if (chapterId != requestedChapterId || currentPublicationKey != publicationKey) return@launch
                if (!isOnUnmeteredNetwork()) {
                    logcat(LogPriority.DEBUG) {
                        "Skipping EPUB complete cache on a metered or unavailable network " +
                            "chapterId=$requestedChapterId"
                    }
                    // Allow a later display/network change to retry the warm-up.
                    completeCacheStarted = false
                    return@launch
                }
                val cachedFile = epubCacheManager.cacheCompleteBook(
                    source = source,
                    bookUrl = bookUrl,
                    publicationKey = publicationKey,
                    acquireLease = true,
                ) ?: return@launch
                leasedFile = cachedFile
                if (chapterId != requestedChapterId || currentPublicationKey != publicationKey) return@launch
                val cachedUri = cachedFile.toURI().toString()
                val paginationSession = withIOContext {
                    publicationResolver.open(
                        request = EpubOpenRequest(
                            mangaId = mangaId,
                            chapterId = chapterId,
                            sourceId = currentSourceId,
                            title = mutableState.value.chapterTitle.orEmpty(),
                            remotePublication = RemotePublicationRef(checkNotNull(currentProviderId), bookUrl),
                            localUri = cachedUri,
                            openSource = EpubOpenRequest.OpenSource.LOCAL,
                            publisherStylesOverride = publisherStylesOverride,
                            publicationKey = publicationKey,
                            persistCache = !isIncognito(),
                        ),
                        initialLocator = null,
                    )
                }
                pendingSession = paginationSession
                if (chapterId != requestedChapterId || currentPublicationKey != publicationKey) {
                    return@launch
                }
                sessionRepository.putForPagination(paginationSession)
                pendingSession = null
                leasedCacheFile?.let(epubCacheManager::release)
                leasedCacheFile = cachedFile
                leasedFile = null
                invalidatePaginationDisplay()
                mutableState.update {
                    it.copy(
                        localEpubUri = cachedUri,
                        bookSizeBytes = cachedFile.length(),
                        paginationSourceVersion = it.paginationSourceVersion + 1,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logcat(LogPriority.WARN, error) {
                    "Failed to open cached EPUB for local pagination chapterId=$chapterId"
                }
            } finally {
                pendingSession?.close()
                leasedFile?.let(epubCacheManager::release)
            }
        }
    }

    private fun refreshPositionsAfterDisplay() {
        if (positionsRefreshStarted) return
        val session = sessionRepository.get(chapterId) ?: return
        positionsRefreshStarted = true
        viewModelScope.launch {
            runCatching { session.positionsController.refresh() }
                .onSuccess { positions ->
                    if (session.positionsController.hasAuthoritativePositions) {
                        latestLocator = latestLocator
                            ?.alignToEpubPositions(positions)
                            ?.also { alignedLocator ->
                                locatorJson = alignedLocator.toJSON().toString()
                            }
                    }
                    applyPublicationPositions(positions)
                    logcat(LogPriority.DEBUG) {
                        "EPUB positions refreshed chapterId=$chapterId " +
                            "authoritative=${session.positionsController.hasAuthoritativePositions} " +
                            "positions=${positions.size}"
                    }
                    latestLocator?.let(locatorUpdates::tryEmit)
                }
                .onFailure { error ->
                    logcat(LogPriority.WARN, error) {
                        "Failed to refresh EPUB positions for chapterId=$chapterId"
                    }
                }
        }
    }

    private fun prefetchNextResourceIfNeeded() {
        if (isIncognito() || state.value.isUsingLocalFile || epubCachePreferences.cacheWholeBook.get()) return
        val session = sessionRepository.get(chapterId) ?: return
        val href = latestLocator?.href?.toString()?.normalizedResourceHref().orEmpty()
        if (lastPrefetchedHref == href) return
        lastPrefetchedHref = href
        viewModelScope.launch {
            runCatching { session.prefetchNextResource(latestLocator) }
                .onFailure { error ->
                    logcat(LogPriority.WARN, error) {
                        "Failed to prefetch next EPUB resource chapterId=$chapterId href=$href"
                    }
                }
        }
    }

    private fun refreshRemoteProgressAfterDisplay() {
        if (remoteProgressChecked || isIncognito() || !epubReaderPreferences.syncRemoteProgression.get()) return
        val chapter = currentChapter ?: return
        val progressAdapter = currentEpubProgressAdapter ?: return
        val localLocatorAtCheck = latestLocator
        val checkStartedAt = System.currentTimeMillis()
        remoteProgressChecked = true
        viewModelScope.launch {
            val remote = runCatching {
                progressAdapter.refreshEpubProgress(mangaId, chapter)
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Failed to refresh EPUB remote progress for chapterId=$chapterId"
                }
            }.getOrNull() ?: return@launch
            if (!EpubRemoteProgressPolicy.isFreshResult(checkStartedAt, remote.checkedAt.time)) {
                logcat(LogPriority.WARN) {
                    "Keeping EPUB remote writes blocked after stale refresh result chapterId=$chapterId " +
                        "checkedAt=${remote.checkedAt.time} startedAt=$checkStartedAt"
                }
                return@launch
            }
            val locator = remote.locatorJson
                ?.let { json -> runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull() }
            val modifiedAt = remote.modifiedAt
            if (locator == null || modifiedAt == null) {
                remoteProgressWriteAllowed = true
                val currentLocator = latestLocator
                val localChangedDuringCheck = localLocatorAtCheck != null &&
                    !localLocatorAtCheck.isSamePaginationLocation(currentLocator)
                if (localChangedDuringCheck && currentLocator != null && currentProgress != null) {
                    remoteProgressWriteBaseline = null
                    syncPersistedProgress(checkNotNull(currentProgress), currentLocator)
                } else {
                    remoteProgressWriteBaseline = currentLocator
                    logcat(LogPriority.DEBUG) {
                        "Confirmed no remote EPUB progress; waiting for local navigation before upload chapterId=$chapterId"
                    }
                }
                return@launch
            }
            val acceptedRemoteLocator = initiallyAcceptedRemoteLocator
            if (acceptedRemoteLocator != null && locator.isSamePaginationLocation(acceptedRemoteLocator)) {
                val acceptedRemoteModifiedAt = initiallyAcceptedRemoteModifiedAt
                val progress = currentProgress
                if (acceptedRemoteModifiedAt != null &&
                    progress != null &&
                    progress.updatedAt.time == acceptedRemoteModifiedAt.time &&
                    modifiedAt.time != acceptedRemoteModifiedAt.time
                ) {
                    val correctedProgress = progress.copy(
                        updatedAt = modifiedAt,
                        lastSyncedAt = modifiedAt,
                    )
                    currentProgress = correctedProgress
                    upsertEpubProgress.await(correctedProgress)
                    initiallyAcceptedRemoteModifiedAt = modifiedAt
                    logcat(LogPriority.DEBUG) {
                        "Corrected accepted initial EPUB remote timestamp chapterId=$chapterId " +
                            "raw=${acceptedRemoteModifiedAt.time} corrected=${modifiedAt.time}"
                    }
                }
                logcat(LogPriority.DEBUG) {
                    "Ignoring already accepted initial EPUB remote progress chapterId=$chapterId " +
                        "remote=${locator.debugProgress()} current=${latestLocator.debugProgress()}"
                }
                remoteProgressWriteAllowed = true
                val currentLocator = latestLocator
                val localChangedDuringCheck = localLocatorAtCheck != null &&
                    !localLocatorAtCheck.isSamePaginationLocation(currentLocator)
                if (localChangedDuringCheck && currentLocator != null && currentProgress != null) {
                    remoteProgressWriteBaseline = null
                    syncPersistedProgress(checkNotNull(currentProgress), currentLocator)
                } else {
                    remoteProgressWriteBaseline = currentLocator
                }
                return@launch
            }
            val currentLocator = latestLocator
            val localChangedDuringCheck = localLocatorAtCheck != null &&
                !localLocatorAtCheck.isSamePaginationLocation(currentLocator)
            when (
                EpubRemoteProgressPolicy.decide(
                    localUpdatedAtMillis = localProgressUpdatedAtSessionOpen,
                    remoteModifiedAtMillis = modifiedAt.time,
                    sameLocation = locator.isSamePaginationLocation(currentLocator),
                    localChangedDuringCheck = localChangedDuringCheck,
                )
            ) {
                EpubRemoteProgressDecision.SAME_LOCATION -> {
                    val acceptedLocator = currentLocator ?: locator
                    val syncedProgress = buildProgress(
                        locator = acceptedLocator,
                        updatedAt = modifiedAt,
                        lastSyncedAt = modifiedAt,
                    )
                    currentProgress = syncedProgress
                    upsertEpubProgress.await(syncedProgress)
                    remoteProgressWriteBaseline = acceptedLocator
                    remoteProgressWriteAllowed = true
                }
                EpubRemoteProgressDecision.KEEP_LOCAL -> {
                    remoteProgressWriteBaseline = null
                    remoteProgressWriteAllowed = true
                    val localLocator = currentLocator ?: return@launch
                    val localProgress = currentProgress ?: return@launch
                    val progressToSync = if (localChangedDuringCheck) {
                        localProgress
                    } else {
                        localProgressUpdatedAtSessionOpen
                            ?.let { localProgress.copy(updatedAt = Date(it)) }
                            ?: localProgress
                    }
                    syncPersistedProgress(progressToSync, localLocator)
                }
                EpubRemoteProgressDecision.KEEP_REMOTE -> {
                    mutableState.update {
                        it.copy(
                            serverTimeOffsetMinutes = remote.serverDate?.serverTimeOffsetMinutes(),
                            remoteProgressConflict = EpubRemoteProgressConflict(
                                locatorJson = locator.toJSON().toString(),
                                progressionPercent = locator.progressionPercent(),
                                sectionTitle = locator.title,
                                modifiedAtMillis = modifiedAt.time,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun onLayoutPreferencesChanged() {
        layoutChangeRevision += 1
        paginationPersistJob?.cancel()
        paginationPersistJob = null
        preserveLocalProgressAfterLayoutChange = true
        mutableState.update { state ->
            state.copy(remoteProgressConflict = null)
        }
        logcat(LogPriority.DEBUG) {
            "Keeping local EPUB progress after layout change chapterId=$chapterId revision=$layoutChangeRevision"
        }
    }

    fun acceptRemoteProgress(): Locator? {
        val conflict = mutableState.value.remoteProgressConflict ?: return null
        val locator = runCatching { Locator.fromJSON(JSONObject(conflict.locatorJson)) }.getOrNull() ?: return null
        mutableState.update { it.copy(remoteProgressConflict = null) }
        val modifiedAt = Date(conflict.modifiedAtMillis)
        val syncedProgress = buildProgress(
            locator = locator,
            updatedAt = modifiedAt,
            lastSyncedAt = modifiedAt,
        )
        currentProgress = syncedProgress
        remoteProgressWriteBaseline = locator
        remoteProgressWriteAllowed = true
        viewModelScope.launch {
            upsertEpubProgress.await(syncedProgress)
        }
        return locator
    }

    fun keepLocalProgress() {
        mutableState.update { it.copy(remoteProgressConflict = null) }
        remoteProgressWriteBaseline = null
        remoteProgressWriteAllowed = true
        val localProgress = currentProgress ?: return
        val localLocator = latestLocator ?: return
        viewModelScope.launch {
            syncPersistedProgress(localProgress, localLocator)
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
        isRtlLayout = false
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
        isRtlLayout = snapshot.pageDirection ==
            koharia.epub.settings.EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT.name
        lastVisualHref = null
        lastVisualPageIndex = null
        lastVisualTotalPages = null

        val publicationKey = currentPublicationKey ?: "chapter:$chapterId"
        val publication = sessionRepository.getForPagination(chapterId)?.publication
        val hasLocalPaginationSource = mutableState.value.isUsingLocalFile ||
            sessionRepository.hasDedicatedPaginationSession(chapterId)
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
        val readingOrder = publication?.readingOrder.orEmpty()
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

        if (!hasLocalPaginationSource && !isComplete) {
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

        return EpubPaginationRequest(
            generation = generation,
            publicationKey = publicationKey,
            layoutKey = snapshot.key,
            layoutSnapshotJson = snapshot.json,
            initialPageCounts = cachedCounts,
            shouldScan = hasLocalPaginationSource && !isComplete,
        )
    }

    fun updateBookPagination(
        generation: Long,
        pageCounts: Map<String, Int>,
        isComplete: Boolean,
    ) {
        if (generation != paginationGeneration) return
        val readingOrder = sessionRepository.getForPagination(chapterId)?.publication?.readingOrder.orEmpty()
        if (readingOrder.isEmpty()) return
        val orderedCounts = pageCounts.orderedFor(readingOrder)
        bookVisualPageCounts = orderedCounts
        if (!isComplete || orderedCounts.size != readingOrder.size) {
            schedulePaginationCachePersist(isComplete = false)
            return
        }

        val totalPages = orderedCounts.values.sum().coerceAtLeast(1)
        val href = lastVisualHref
            ?: latestLocator?.href?.toString()?.normalizedResourceHref()
        val currentPage = lastVisualPageIndex
            ?.let { exactVisualPage(href, it, readingOrder, orderedCounts) }
            ?: pageFromLocator(latestLocator, readingOrder, orderedCounts)
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
            schedulePaginationCachePersist(isComplete = true)
        }
    }

    private fun schedulePaginationCachePersist(isComplete: Boolean) {
        paginationPersistJob?.cancel()
        paginationPersistJob = viewModelScope.launch {
            if (!isComplete) delay(PAGINATION_CACHE_WRITE_DEBOUNCE_MS)
            persistPaginationCache(isComplete)
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
        val resourcePages = pageCounts.pageCountFor(href) ?: return null
        val progression = (locator.locations.progression as? Number)?.toDouble() ?: 0.0
        var pageIndex = (progression * resourcePages).roundToInt().coerceIn(0, resourcePages - 1)
        if (isRtlLayout && pageIndex > 0) pageIndex -= 1
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
            val resourcePages = pageCounts.pageCountFor(resourceHref) ?: return null
            if (resourceHref.isSameResourceHref(href)) {
                return pagesBefore + (pageIndex + 1).coerceIn(1, resourcePages)
            }
            pagesBefore += resourcePages
        }
        return null
    }

    private fun Map<String, Int>.pageCountFor(href: String): Int? {
        return entries.firstOrNull { (candidate, count) ->
            count > 0 && candidate.isSameResourceHref(href)
        }?.value
    }

    private fun paginationReadingOrder(): List<Link> {
        return sessionRepository.getForPagination(chapterId)
            ?.publication
            ?.readingOrder
            .orEmpty()
            .ifEmpty {
                sessionRepository.get(chapterId)?.publication?.readingOrder.orEmpty()
            }
    }

    private fun visualPagePairForLocator(locator: Locator): Pair<Int, Int>? {
        val readingOrder = paginationReadingOrder()
        if (readingOrder.isEmpty() || bookVisualPageCounts.size != readingOrder.size) return null
        val totalPages = bookVisualPageCounts.values.sum().takeIf { it > 0 } ?: return null
        val currentPage = pageFromLocator(locator, readingOrder, bookVisualPageCounts)
            ?.coerceIn(1, totalPages)
            ?: return null
        return currentPage to totalPages
    }

    private fun resetVisualPagination() {
        paginationPersistJob?.cancel()
        paginationPersistJob = null
        visualPageNumber = null
        lastVisualHref = null
        lastVisualPageIndex = null
        lastVisualTotalPages = null
        bookVisualPageCounts = emptyMap()
        paginationLayoutKey = null
        paginationLayoutJson = null
        isRtlLayout = false
    }

    fun dismissServerTimeWarning() {
        mutableState.update { it.copy(serverTimeOffsetMinutes = null) }
    }

    fun locatorAtPosition(index: Int): Locator? = publicationPositions.getOrNull(index)

    suspend fun locatorAtProgression(progression: Double): Locator? {
        val target = progression.coerceIn(0.0, 1.0)
        sessionRepository.get(chapterId)
            ?.publication
            ?.locateProgression(target)
            ?.let { locator ->
                logcat(LogPriority.DEBUG) {
                    "EPUB progression locate chapterId=$chapterId target=$target " + locator.debugProgress()
                }
                return locator
            }
        if (publicationPositions.isEmpty()) return null
        val index = (target * (publicationPositions.size - 1))
            .roundToInt()
        return publicationPositions.getOrNull(index)?.also { locator ->
            logcat(LogPriority.DEBUG) {
                "EPUB progression locate fallback chapterId=$chapterId target=$target index=$index " +
                    locator.debugProgress()
            }
        }
    }

    fun previewProgressionSeek(requestedProgression: Double, locator: Locator) {
        val persistentLocator = sessionRepository.get(chapterId)
            ?.publication
            ?.toPersistentLocator(locator)
            ?: locator
        val progression = persistentLocator.totalProgressionValue()
            ?: requestedProgression.coerceIn(0.0, 1.0)
        val visualPagePair = visualPagePairForLocator(persistentLocator)
        visualPagePair?.first?.let { visualPageNumber = it }
        mutableState.update {
            it.copy(
                progression = progression,
                progressionPercent = (progression * 100).roundToInt().coerceIn(0, 100),
                currentPosition = persistentLocator.positionIn(publicationPositions),
                currentVisualPage = visualPagePair?.first,
                totalVisualPages = visualPagePair?.second,
                paginationPhase = if (visualPagePair != null) {
                    EpubPaginationPhase.READY
                } else {
                    it.paginationPhase
                },
            )
        }
        logcat(LogPriority.DEBUG) {
            "EPUB progression preview chapterId=$chapterId requested=$requestedProgression " +
                "${persistentLocator.debugProgress()} visual=${visualPagePair?.first}/${visualPagePair?.second}"
        }
    }

    fun restoreCurrentProgressDisplay() {
        latestLocator?.let(::updateLocator)
    }

    private fun applyPublicationPositions(positions: List<Locator>) {
        if (positions.isEmpty()) return
        publicationPositions = positions
        publicationPositionByHref = buildMap {
            positions.forEachIndexed { index, locator ->
                locator.href.toString().hrefCandidates().forEach { href ->
                    putIfAbsent(href, index + 1)
                }
            }
        }
        latestLocator?.let { locator ->
            val position = locator.positionIn(positions)
            val progression = locator.totalProgressionValue()
                ?: position.toProgression(positions.size)
            mutableState.update {
                it.copy(
                    progression = progression,
                    progressionPercent = (progression * 100).roundToInt().coerceIn(0, 100),
                    currentPosition = position,
                    totalPositions = positions.size.coerceAtLeast(1),
                )
            }
        }
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
        completeCacheJob?.cancel()
        completeCacheJob = null
        paginationPersistJob?.cancel()
        paginationPersistJob = null
        closeImagePreview()
        dismissFootnote()
        locatorPersistenceJob.cancel()
        val finalPositions = authoritativePublicationPositions()
        sessionRepository.remove(chapterId)?.close()
        releaseCacheLeases()

        val locator = latestLocator
        if (locator != null && !isIncognito()) {
            sessionReleaseScope.launch {
                runCatching { persistLocator(locator, finalPositions) }
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
        completeCacheJob?.cancel()
        paginationPersistJob?.cancel()
        imageLoadJob?.cancel()
        footnoteLoadJob?.cancel()
        imageRequestTracker.invalidate()
        searchIterator?.close()
        searchIterator = null
        releaseCacheLeases()
        super.onCleared()
    }

    private fun releaseCacheLeases() {
        leasedCacheFile?.let(epubCacheManager::release)
        leasedCacheFile = null
        leasedPublicationKey?.let { publicationKey ->
            currentSourceId.takeIf { it > 0L }
                ?.let { sourceId -> epubCacheManager.releasePublication(sourceId, publicationKey) }
        }
        leasedPublicationKey = null
    }

    private fun isOnUnmeteredNetwork(): Boolean {
        val connectivityManager = application.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
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
        if (!remoteProgressWriteAllowed) return
        val progressAdapter = currentEpubProgressAdapter ?: return
        val bookUrl = localProgress.bookUrl ?: currentBookUrl ?: return
        val positions = authoritativePublicationPositions() ?: return
        runCatching {
            progressAdapter.pushEpubProgress(
                resourceId = bookUrl,
                locator = locator,
                positions = positions,
                modifiedAt = localProgress.updatedAt,
            )
            val syncedProgress = localProgress.copy(lastSyncedAt = localProgress.updatedAt)
            currentProgress = syncedProgress
            upsertEpubProgress.await(syncedProgress)
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) {
                "Failed to sync existing remote EPUB progression for chapterId=${localProgress.chapterId}"
            }
        }
    }

    private suspend fun persistLocator(
        locator: Locator,
        positionsOverride: List<Locator>? = null,
    ) = progressPersistenceMutex.withLock {
        val chapterId = chapterId.takeIf { it > 0 } ?: return@withLock
        mangaId.takeIf { it > 0 } ?: return@withLock
        val previousProgress = currentProgress
        val previousLocator = previousProgress?.toLocatorOrNull()
        val progressUpdatedAt = if (previousLocator != null && locator.isSamePaginationLocation(previousLocator)) {
            previousProgress.updatedAt
        } else {
            Date()
        }
        val progress = buildProgress(
            locator = locator,
            updatedAt = progressUpdatedAt,
            lastSyncedAt = previousProgress?.lastSyncedAt,
        )
        upsertEpubProgress.await(progress)
        currentProgress = progress
        persistPaginationCache(
            isComplete = mutableState.value.paginationPhase in setOf(
                EpubPaginationPhase.CACHED,
                EpubPaginationPhase.READY,
            ),
        )
        markChapterCompletedIfNeeded(locator)

        val bookUrl = progress.bookUrl
        if (bookUrl != null && epubReaderPreferences.syncRemoteProgression.get() && remoteProgressWriteAllowed) {
            val writeBaseline = remoteProgressWriteBaseline
            if (writeBaseline != null && locator.isSamePaginationLocation(writeBaseline)) return@withLock
            remoteProgressWriteBaseline = null
            val progressAdapter = currentEpubProgressAdapter ?: return@withLock
            val positions = positionsOverride ?: authoritativePublicationPositions() ?: return@withLock
            runCatching {
                progressAdapter.pushEpubProgress(
                    resourceId = bookUrl,
                    locator = locator,
                    positions = positions,
                    modifiedAt = progressUpdatedAt,
                )
                val syncedProgress = progress.copy(lastSyncedAt = progressUpdatedAt)
                upsertEpubProgress.await(syncedProgress)
                currentProgress = syncedProgress
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Failed to push remote EPUB progression for chapterId=$chapterId"
                }
            }
        }
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
        remoteProgress: RemoteEpubProgression?,
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

    private fun authoritativePublicationPositions(): List<Locator>? {
        val controller = sessionRepository.get(chapterId)?.positionsController ?: return null
        if (!controller.hasAuthoritativePositions) return null
        return publicationPositions.takeIf { it.isNotEmpty() }
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

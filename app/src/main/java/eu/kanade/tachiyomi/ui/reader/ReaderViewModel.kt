package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.crash.CrashDiagnostics
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.DoublePageSaveMode
import eu.kanade.tachiyomi.ui.reader.setting.MergedPageExportOptions
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DoublePageProgressPolicy
import eu.kanade.tachiyomi.ui.reader.viewer.pager.MergedPageImage
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import eu.kanade.tachiyomi.util.system.toast
import koharia.connection.ConnectionChapterMetadata
import koharia.connection.ConnectionLocalFileAdapter
import koharia.connection.ConnectionPageProgressAdapter
import koharia.connection.ConnectionPublicationAdapter
import koharia.connection.ConnectionRawDownloadAdapter
import koharia.connection.SharedAppPreferences
import koharia.connection.isConnectionLibraryEntry
import koharia.document.DocumentRenderSettings
import koharia.domain.epub.interactor.GetEpubProgress
import koharia.epub.progress.EpubPageProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.ScopedPreferenceStore
import tachiyomi.core.common.preference.SessionPreferenceStore
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Presenter used by the activity to perform background operations.
 */
class ReaderViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val imageSaver: ImageSaver = Injekt.get(),
    globalReaderPreferenceStore: ScopedPreferenceStore = Injekt.get(),
    globalBasePreferences: BasePreferences = Injekt.get(),
    globalDownloadPreferences: DownloadPreferences = Injekt.get(),
    globalTrackPreferences: TrackPreferences = Injekt.get(),
    private val trackChapter: TrackChapter = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val setMangaViewerFlags: SetMangaViewerFlags = Injekt.get(),
    globalLibraryPreferences: LibraryPreferences = Injekt.get(),
    private val getEpubProgress: GetEpubProgress = Injekt.get(),
    private val sharedAppPreferences: SharedAppPreferences = Injekt.get(),
) : ViewModel() {

    private val resourceCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resourcesReleased = AtomicBoolean(false)
    private var readingSource: eu.kanade.tachiyomi.source.Source? = null

    private val persistentReaderSettingsStore =
        globalReaderPreferenceStore
    private val persistentReaderPreferences = ReaderPreferences(persistentReaderSettingsStore)
    private val readerSettingsStore = SessionPreferenceStore(
        backingStore = persistentReaderSettingsStore,
        persistChanges = persistentReaderPreferences.persistReaderSettingsChanges.get(),
    )
    val readerPreferences = ReaderPreferences(readerSettingsStore)
    val persistReaderSettingsChanges = persistentReaderPreferences.persistReaderSettingsChanges
    private val basePreferences: BasePreferences =
        globalBasePreferences
    private val downloadPreferences: DownloadPreferences =
        globalDownloadPreferences
    private val trackPreferences: TrackPreferences =
        globalTrackPreferences
    private val libraryPreferences: LibraryPreferences =
        globalLibraryPreferences

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    /**
     * The manga loaded in the reader. It can be null when instantiated for a short time.
     */
    val manga: Manga?
        get() = state.value.manga

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = savedState.get<Long>("chapter_id") ?: -1L
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    /**
     * The visible page index of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterPageIndex = savedState.get<Int>("page_index") ?: -1
        set(value) {
            savedState["page_index"] = value
            field = value
        }

    /**
     * The chapter loader for the loaded manga. It'll be null until [manga] is set.
     */
    private var loader: ChapterLoader? = null

    private var documentSettingsProvider: () -> DocumentRenderSettings = {
        DocumentRenderSettings.DEFAULT
    }

    private var documentRefreshJob: Job? = null
    private val documentRefreshGeneration = AtomicLong(0L)

    /**
     * The time the chapter was started reading
     */
    private var chapterReadStartTime: Long? = null

    private var chapterToDownload: Download? = null
    private val currentChapterAutoCacheRequests = mutableSetOf<Long>()
    private val remoteProgressChecksStarted = mutableSetOf<Long>()
    private val connectionReadingSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val explicitOpeningChapterId = savedState.get<Long>("chapter")
        ?.takeIf { savedState.get<Boolean>("explicit_page_selection") == true }
    private val navigatedConnectionChapters = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val confirmedConnectionPages = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    private val progressTasks = ReaderProgressTasks(viewModelScope)
    private val remoteProgressWritesAllowed = mutableSetOf<Long>()
    private val remoteProgressVersionsHandled = mutableSetOf<String>()
    private val remoteProgressOpeningPages = mutableMapOf<Long, Int>()

    private val usesReadingQueue =
        savedState.get<String>(koharia.connection.ConnectionReadingQueueController.CONTEXT) != null
    private val unfilteredChapterList by lazy {
        val manga = manga!!
        runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = false) }
            .filter { !usesReadingQueue || it.id == chapterId }
    }

    /**
     * Chapter list for the active manga. It's retrieved lazily and should be accessed for the first
     * time in a background thread to avoid blocking the UI.
     */
    private val chapterList by lazy {
        val manga = manga!!
        val chapters = runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true) }

        val selectedChapter = chapters.find { it.id == chapterId }
            ?: error("Requested chapter of id $chapterId not found in chapter list")

        val chaptersForReader = when {
            usesReadingQueue -> listOf(selectedChapter)
            (readerPreferences.skipRead.get() || readerPreferences.skipFiltered.get()) -> {
                val filteredChapters = chapters.filterNot {
                    when {
                        readerPreferences.skipRead.get() && it.read -> true
                        readerPreferences.skipFiltered.get() -> {
                            (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !it.read) ||
                                (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && it.read) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED &&
                                        !downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED &&
                                        downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !it.bookmark) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && it.bookmark)
                        }
                        else -> false
                    }
                }

                if (filteredChapters.any { it.id == chapterId }) {
                    filteredChapters
                } else {
                    filteredChapters + listOf(selectedChapter)
                }
            }
            else -> chapters
        }

        chaptersForReader
            .sortedWith(getChapterSort(manga, sortDescending = false))
            .run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(selectedChapter)
                } else {
                    this
                }
            }
            .run {
                if (basePreferences.downloadedOnly.get() &&
                    sourceManager.get(manga.source) !is ConnectionLocalFileAdapter
                ) {
                    val publicationAdapter = sourceManager.get(manga.source) as? ConnectionPublicationAdapter
                    filter { chapter ->
                        downloadManager.isChapterDownloaded(
                            chapter.name,
                            chapter.scanlator,
                            chapter.url,
                            manga.title,
                            manga.source,
                        ) || publicationAdapter?.hasCompleteCachedPublication(chapter) == true
                    }
                } else {
                    this
                }
            }
            .map { it.toDbChapter() }
            .map(::ReaderChapter)
    }

    private val incognitoMode: Boolean by lazy {
        koharia.connection.DevReadOnlyValidation.enabled ||
            basePreferences.incognitoMode.get()
    }
    val isIncognitoSession get() = incognitoMode
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading.get()
    private val cacheCurrentChapterWhileReading = downloadPreferences.cacheCurrentChapterWhileReading.get()

    init {
        persistReaderSettingsChanges.changes()
            .onEach(readerSettingsStore::setPersistChanges)
            .launchIn(viewModelScope)

        // To save state
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { currentChapter ->
                if (chapterPageIndex >= 0) {
                    // Restore from SavedState
                    currentChapter.requestedPage = chapterPageIndex
                } else if (!currentChapter.chapter.read) {
                    currentChapter.requestedPage = currentChapter.chapter.last_page_read
                }
                chapterId = currentChapter.chapter.id!!
                synchronized(remoteProgressOpeningPages) {
                    remoteProgressOpeningPages.putIfAbsent(chapterId, currentChapter.requestedPage)
                }
                cacheCurrentChapterForOffline(currentChapter)
            }
            .launchIn(viewModelScope)
    }

    override fun onCleared() {
        releaseReaderResources()
    }

    /**
     * Stops reader work and releases file-backed resources away from the main thread. This is
     * deliberately safe to call from both the activity and [onCleared].
     */
    fun releaseReaderResources() {
        if (!resourcesReleased.compareAndSet(false, true)) return

        viewModelScope.coroutineContext.cancelChildren()
        val currentChapters = state.value.viewerChapters
        val pendingDownload = chapterToDownload

        resourceCleanupScope.launch {
            val startedAt = SystemClock.uptimeMillis()
            try {
                currentChapters?.unref()
            } catch (error: Throwable) {
                CrashDiagnostics.recordNonFatal(
                    Injekt.get<Application>(),
                    "reader.resource.cleanup",
                    error,
                )
            } finally {
                CrashDiagnostics.recordSlowOperation(
                    Injekt.get<Application>(),
                    "reader.resource.cleanup",
                    SystemClock.uptimeMillis() - startedAt,
                )
            }

            try {
                pendingDownload?.let { downloadManager.addDownloadsToStartOfQueue(listOf(it)) }
            } catch (error: Throwable) {
                CrashDiagnostics.recordNonFatal(
                    Injekt.get<Application>(),
                    "reader.download.queue.restore",
                    error,
                )
            } finally {
                resourceCleanupScope.cancel()
            }
        }
    }

    /**
     * Called when the user pressed the back button and is going to leave the reader. Used to
     * trigger deletion of the downloaded chapters.
     */
    fun onActivityFinish() {
        deletePendingChapters()
    }

    fun setPersistReaderSettingsChanges(enabled: Boolean) {
        readerSettingsStore.setPersistChanges(enabled)
        persistReaderSettingsChanges.set(enabled)
    }

    fun setSessionReaderSettingsPersistence(enabled: Boolean) {
        readerSettingsStore.setPersistChanges(enabled)
    }

    /** Supplies the activity's session-backed EPUB preferences to document page loaders. */
    fun setDocumentRenderSettingsProvider(provider: () -> DocumentRenderSettings) {
        documentSettingsProvider = provider
    }

    /** Recreates document pages after a typography or theme preference changes. */
    fun onDocumentLayoutPreferencesChanged() {
        val readerChapter = getCurrentChapter() ?: return
        val pageLoader = readerChapter.pageLoader ?: return
        val generation = documentRefreshGeneration.incrementAndGet()
        documentRefreshJob?.cancel()
        documentRefreshJob = viewModelScope.launchIO {
            val oldPageCount = readerChapter.pages?.size ?: return@launchIO
            val oldPageIndex = readerChapter.requestedPage.coerceIn(0, (oldPageCount - 1).coerceAtLeast(0))
            val refreshedPages = pageLoader.refreshPages() ?: return@launchIO
            if (
                refreshedPages.isEmpty() ||
                generation != documentRefreshGeneration.get() ||
                getCurrentChapter() !== readerChapter
            ) {
                return@launchIO
            }

            refreshedPages.forEach { it.chapter = readerChapter }
            val newPageIndex = if (oldPageCount <= 1 || refreshedPages.size <= 1) {
                0
            } else {
                (oldPageIndex.toLong() * (refreshedPages.size - 1) / (oldPageCount - 1))
                    .toInt()
                    .coerceIn(0, refreshedPages.lastIndex)
            }
            val committed = withUIContext {
                if (
                    generation == documentRefreshGeneration.get() &&
                    getCurrentChapter() === readerChapter
                ) {
                    readerChapter.requestedPage = newPageIndex
                    readerChapter.state = ReaderChapter.State.Loaded(refreshedPages)
                    pageLoader.setActivePage(refreshedPages[newPageIndex])
                    eventChannel.send(Event.DocumentPagesRefreshed(refreshedPages[newPageIndex]))
                    true
                } else {
                    false
                }
            }
            if (committed && generation == documentRefreshGeneration.get()) {
                persistDocumentPageCount(readerChapter)
            }
        }
    }

    /**
     * Whether this presenter is initialized yet.
     */
    fun needsInit(): Boolean {
        return manga == null
    }

    /**
     * Initializes this presenter with the given [mangaId] and [initialChapterId]. This method will
     * fetch the manga from the database and initialize the initial chapter.
     */
    suspend fun init(mangaId: Long, initialChapterId: Long): Result<Boolean> {
        if (!needsInit()) return Result.success(true)
        return withIOContext {
            try {
                val manga = getManga.await(mangaId)
                if (manga != null) {
                    savedState["source_id"] = manga.source
                    sourceManager.isInitialized.first { it }
                    mutableState.update { it.copy(manga = manga) }
                    if (chapterId == -1L) chapterId = initialChapterId

                    val context = Injekt.get<Application>()
                    readingSource = sourceManager.get(manga.source)
                    val source = sourceManager.getOrStub(manga.source)
                    loader = ChapterLoader(
                        context = context,
                        downloadManager = downloadManager,
                        downloadProvider = downloadProvider,
                        manga = manga,
                        source = source,
                        documentSettingsProvider = { documentSettingsProvider() },
                    )

                    val initialChapter = chapterList.first { chapterId == it.chapter.id }
                    bindProgressSession(initialChapter)
                    val initialPageIndex = chapterPageIndex.takeIf { it >= 0 }
                        ?: restoreDivinaEpubPage(initialChapter)
                    loadChapter(
                        loader = loader!!,
                        chapter = initialChapter,
                        initialPageIndex = initialPageIndex,
                    )
                    Result.success(true)
                } else {
                    // Unlikely but okay
                    Result.success(false)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                Result.failure(e)
            }
        }
    }

    /**
     * Loads the given [chapter] with this [loader] and updates the currently active chapters.
     * Callers must handle errors.
     */
    private suspend fun loadChapter(
        loader: ChapterLoader,
        chapter: ReaderChapter,
        initialPageIndex: Int? = null,
    ): ViewerChapters {
        bindProgressSession(chapter).checkCurrent()
        try {
            loader.loadChapter(
                chapter,
                initialPageIndex,
                ::resolveConnectionProgressBeforePageActivation,
                onArchiveLoadingStage = { stage ->
                    mutableState.update { it.copy(archiveLoadingStage = stage) }
                },
            )
        } finally {
            mutableState.update { it.copy(archiveLoadingStage = null) }
        }
        persistDocumentPageCount(chapter)

        val chapterPos = chapterList.indexOf(chapter)
        val newChapters = ViewerChapters(
            chapter,
            chapterList.getOrNull(chapterPos - 1),
            chapterList.getOrNull(chapterPos + 1),
        )

        withUIContext {
            mutableState.update {
                // Add new references first to avoid unnecessary recycling
                newChapters.ref()
                it.viewerChapters?.unref()

                chapterToDownload = cancelQueuedDownloads(newChapters.currChapter)
                it.copy(
                    viewerChapters = newChapters,
                    bookmarked = newChapters.currChapter.chapter.bookmark,
                )
            }
        }
        return newChapters
    }

    private suspend fun resolveConnectionProgressBeforePageActivation(
        readerChapter: ReaderChapter,
        pages: List<ReaderPage>,
    ) {
        if (incognitoMode || readerChapter.pageLoader?.supportsRemoteProgress == false) return
        val manga = manga ?: return
        val adapter = connectionPageProgressAdapter(readerChapter) ?: return
        // PDF progress is measured against the loaded file, before negotiating a server position.
        persistDocumentPageCount(readerChapter)
        val chapterId = readerChapter.chapter.id ?: return
        synchronized(remoteProgressOpeningPages) {
            remoteProgressOpeningPages.putIfAbsent(
                chapterId,
                readerChapter.requestedPage.coerceIn(0, pages.lastIndex),
            )
        }
        if (!remoteProgressChecksStarted.add(chapterId)) return
        refreshConnectionBookProgress(
            progressAdapter = adapter,
            manga = manga,
            readerChapter = readerChapter,
            beforePageActivation = true,
        )
    }

    private suspend fun persistDocumentPageCount(chapter: ReaderChapter) {
        val chapterId = chapter.chapter.id ?: return
        val pageCount = chapter.pageLoader?.progressPageCount ?: return
        val updatedMemo = ConnectionChapterMetadata.withPagesCount(chapter.chapter.memo, pageCount)
        if (updatedMemo == chapter.chapter.memo) return
        persistReaderChapter(chapter, ChapterUpdate(id = chapterId, memo = updatedMemo))
        chapter.chapter.memo = updatedMemo
    }

    /**
     * Called when the user changed to the given [chapter] when changing pages from the viewer.
     * It's used only to set this chapter as active.
     */
    private fun loadNewChapter(chapter: ReaderChapter) {
        val loader = loader ?: return

        viewModelScope.launchIO {
            logcat { "Loading ${chapter.chapter.url}" }

            updateHistory()
            restartReadTimer()

            try {
                loadChapter(loader, chapter)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * Called when the user is going to load the prev/next chapter through the toolbar buttons.
     */
    private suspend fun loadAdjacent(chapter: ReaderChapter) {
        val loader = loader ?: return

        logcat { "Loading adjacent ${chapter.chapter.url}" }

        mutableState.update { it.copy(isLoadingAdjacentChapter = true) }
        try {
            withIOContext {
                loadChapter(loader, chapter)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            logcat(LogPriority.ERROR, e)
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
    }

    private val transitionPreloads = java.util.concurrent.ConcurrentHashMap.newKeySet<ReaderChapter>()

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    suspend fun preload(chapter: ReaderChapter, allowPdfDownload: Boolean = false) {
        if (chapter.state is ReaderChapter.State.Loaded ||
            (chapter.state == ReaderChapter.State.Loading && !allowPdfDownload)
        ) {
            return
        }

        if (chapter.pageLoader?.isLocal == false) {
            val manga = manga ?: return
            val dbChapter = chapter.chapter
            val isDownloaded = downloadManager.isChapterDownloaded(
                dbChapter.name,
                dbChapter.scanlator,
                dbChapter.url,
                manga.title,
                manga.source,
                skipCache = true,
            )
            if (isDownloaded) {
                chapter.state = ReaderChapter.State.Wait
            }
        }

        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error &&
            !(allowPdfDownload && chapter.state == ReaderChapter.State.Loading)
        ) {
            return
        }

        val loader = loader ?: return
        if (allowPdfDownload && !transitionPreloads.add(chapter)) return
        try {
            logcat { "Preloading ${chapter.chapter.url}" }
            bindProgressSession(chapter).checkCurrent()
            loader.loadChapter(chapter, allowPdfDownload = allowPdfDownload)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return
        } finally {
            if (allowPdfDownload) transitionPreloads.remove(chapter)
        }
        if (chapter.state is ReaderChapter.State.Loaded) {
            eventChannel.trySend(Event.ReloadViewerChapters)
        }
    }

    private var pagerRecreationState: eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerLayoutState? = null

    fun savePagerRecreationState(state: eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerLayoutState?) {
        pagerRecreationState = state
    }

    fun consumePagerRecreationState(
        chapter: ReaderChapter,
    ): eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerLayoutState? {
        val state = pagerRecreationState
        pagerRecreationState = null
        return state?.takeIf { it.anchor.chapter === chapter && chapter.pages?.contains(it.anchor) == true }
    }

    fun onViewerLoaded(viewer: Viewer?) {
        mutableState.update {
            it.copy(viewer = viewer)
        }
    }

    /**
     * Called every time a page changes on the reader. Used to mark the flag of chapters being
     * read, update tracking services, enqueue downloaded chapter deletion, and updating the active chapter if this
     * [page]'s chapter is different from the currently active.
     */
    fun onPageSelected(page: ReaderPage) {
        // InsertPage doesn't change page progress
        if (page is InsertPage) {
            return
        }

        onPagesSelected(listOf(page))
    }

    fun onPagesSelected(visiblePages: List<ReaderPage>) {
        val physicalPages = visiblePages.filterNot { it is InsertPage }.distinctBy { it.index }
        val page = physicalPages.maxByOrNull { it.index } ?: return

        val selectedChapter = page.chapter
        val pages = selectedChapter.pages ?: return
        if (physicalPages.any { it.chapter !== selectedChapter }) return
        selectedChapter.requestedPage = page.index
        chapterPageIndex = page.index
        selectedChapter.pageLoader?.setActivePages(physicalPages)
        mutableState.update {
            it.copy(
                currentPage = page.index + 1,
                visiblePageStart = physicalPages.minOf { visiblePage -> visiblePage.index } + 1,
                visiblePageEnd = page.index + 1,
            )
        }

        // Save last page read and mark as read if needed
        progressTasks.submit {
            readerProgressAction { updateChapterProgress(selectedChapter, page) }
        }

        if (selectedChapter != getCurrentChapter()) {
            logcat { "Setting ${selectedChapter.chapter.url} as active" }
            loadNewChapter(selectedChapter)
        }

        val inDownloadRange = page.number.toDouble() / pages.size > 0.25
        if (inDownloadRange) {
            downloadNextChapters()
        }

        eventChannel.trySend(Event.PageChanged)
    }

    fun onPagesActivated(visiblePages: List<ReaderPage>, anchorPage: ReaderPage? = null) {
        val physicalPages = visiblePages.filterNot { it is InsertPage }.distinctBy { it.index }
        val chapter = physicalPages.firstOrNull()?.chapter ?: return
        if (physicalPages.any { it.chapter !== chapter }) return
        chapter.pageLoader?.setActivePages(physicalPages)
        val anchor = anchorPage?.takeIf { candidate ->
            candidate.chapter === chapter && physicalPages.any { it.index == candidate.index }
        }
        val displayPage = DoublePageProgressPolicy.activationDisplayPage(physicalPages, anchor) ?: return
        val visiblePageEnd = DoublePageProgressPolicy.visiblePageEnd(physicalPages)?.index?.plus(1) ?: return
        anchor?.let { stableAnchor ->
            stableAnchor.chapter.chapter.id?.let { chapterId ->
                synchronized(remoteProgressOpeningPages) {
                    remoteProgressOpeningPages.putIfAbsent(chapterId, stableAnchor.index)
                }
            }
        }
        mutableState.update {
            it.copy(
                currentPage = displayPage.index + 1,
                visiblePageStart = physicalPages.minOf { page -> page.index } + 1,
                visiblePageEnd = visiblePageEnd,
            )
        }
    }

    fun onPageDisplayed(page: ReaderPage) {
        onPagesDisplayed(listOf(page))
    }

    fun onPagesDisplayed(visiblePages: List<ReaderPage>) {
        val physicalPages = visiblePages.filterNot { it is InsertPage }.distinctBy { it.index }
        val page = physicalPages.maxByOrNull { it.index } ?: return
        page.chapter.pageLoader?.onPagesDisplayed(physicalPages)
        val currentChapter = getCurrentChapter() ?: return
        if (currentChapter !== page.chapter || physicalPages.none { it.index == currentChapter.requestedPage }) return
        logcat {
            "MangaStartup: active images displayed chapterId=${page.chapter.chapter.id} " +
                "pages=${physicalPages.joinToString { it.number.toString() }}"
        }
        if (incognitoMode) return
        if (page.chapter.pageLoader?.supportsRemoteProgress == false) {
            progressTasks.submit {
                readerProgressAction { updateChapterProgress(currentChapter, page, confirmed = true) }
            }
            return
        }
        val manga = manga ?: return
        val localAdapter = connectionPageProgressAdapter(currentChapter)
        val displayedChapterId = currentChapter.chapter.id ?: return
        val previousDisplayed = confirmedConnectionPages.put(displayedChapterId, page.index)
        if (previousDisplayed != null &&
            previousDisplayed != page.index
        ) {
            navigatedConnectionChapters.add(displayedChapterId)
        }
        val localEventJob = if (previousDisplayed != page.index) {
            val displayedAt = System.currentTimeMillis()
            val chapterUrl = currentChapter.chapter.url
            val pageCount = currentChapter.pages?.size ?: 0
            progressTasks.submit {
                runCatching {
                    if (connectionReadingSessions.add(displayedChapterId)) {
                        localAdapter?.beginReadingSession(chapterUrl)
                    }
                    updateChapterProgress(currentChapter, page, confirmed = true) { commitChapter ->
                        if (localAdapter == null) {
                            commitChapter()
                        } else {
                            localAdapter.commitLocalPageProgress(
                                chapterUrl,
                                page.index,
                                pageCount,
                                displayedAt,
                                initialPage =
                                previousDisplayed == null && displayedChapterId != explicitOpeningChapterId,
                                commitChapter,
                            )
                        }
                    }
                }.onFailure { error ->
                    confirmedConnectionPages.remove(displayedChapterId, page.index)
                    if (error is CancellationException) throw error
                    logcat(LogPriority.WARN, error) { "Failed to persist confirmed connection reading" }
                    withUIContext { Injekt.get<Application>().toast(MR.strings.epub_reader_progress_save_failed) }
                }
            }
        } else {
            null
        }
        val progressAdapter = connectionPageProgressAdapter(currentChapter) ?: return
        val currentChapterId = currentChapter.chapter.id ?: return
        if (!remoteProgressChecksStarted.add(currentChapterId)) return

        viewModelScope.launchIO {
            localEventJob?.join()
            refreshConnectionBookProgress(progressAdapter, manga, currentChapter)
        }
    }

    private fun scheduleRemoteProgressRetry(
        adapter: koharia.connection.ConnectionReaderPageProgressAdapter,
        manga: Manga,
        readerChapter: ReaderChapter,
    ) {
        val chapterId = readerChapter.chapter.id ?: return
        viewModelScope.launchIO {
            kotlinx.coroutines.delay(5_000)
            if (getCurrentChapter() !== readerChapter) {
                withUIContext { remoteProgressChecksStarted.remove(chapterId) }
                return@launchIO
            }
            if (incognitoMode) return@launchIO
            refreshConnectionBookProgress(adapter, manga, readerChapter)
        }
    }

    private suspend fun refreshConnectionBookProgress(
        progressAdapter: koharia.connection.ConnectionReaderPageProgressAdapter,
        manga: Manga,
        readerChapter: ReaderChapter,
        beforePageActivation: Boolean = false,
    ) {
        if (koharia.connection.DevReadOnlyValidation.enabled ||
            readerChapter.pageLoader?.supportsRemoteProgress == false
        ) {
            return
        }
        val chapter = readerChapter.chapter
        val chapterId = chapter.id ?: return
        logcat { "MangaStartup: remote progress check start chapterId=$chapterId" }
        val remote = try {
            progressAdapter.pullPageProgress(
                chapterUrl = chapter.url,
                chapterMemo = chapter.memo,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            logcat(LogPriority.WARN, error) {
                "MangaStartup: remote progress check failed; keeping writes blocked chapterId=$chapterId"
            }
            scheduleRemoteProgressRetry(progressAdapter, manga, readerChapter)
            return
        }
        checkNotNull(readerChapter.progressSession).checkCurrent()
        logcat {
            "MangaStartup: remote progress check complete chapterId=$chapterId " +
                "found=${remote != null}"
        }
        if (remote == null) {
            logcat(LogPriority.WARN) {
                "MangaStartup: remote progress unavailable; keeping writes blocked chapterId=$chapterId"
            }
            return
        }

        val oldMemo = chapter.memo
        val oldPublicationVersion = remote.previousPublicationVersion
        val updatedMemo = remote.updatedChapterMemo
        val opensAsImagePages = remote.canOpenAsPages
        val newPublicationVersion = remote.publicationVersion
        val publicationMetadataInitialized = oldPublicationVersion == null && newPublicationVersion != null
        val publicationChanged = hasPublicationChanged(oldPublicationVersion, newPublicationVersion)
        var pages = readerChapter.pages ?: return
        val pageCountChanged = remote.totalPages > 0 && remote.totalPages != pages.size
        val pageLoader = readerChapter.pageLoader ?: return

        if (publicationMetadataInitialized) {
            logcat {
                "MangaStartup: publication metadata initialized; preserving visible page list chapterId=$chapterId"
            }
        }

        if (publicationChanged || pageCountChanged) {
            pageLoader.invalidatePageListCache()
        }
        if (updatedMemo != oldMemo) {
            persistReaderChapter(readerChapter, ChapterUpdate(id = chapterId, memo = updatedMemo))
            chapter.memo = updatedMemo
        }
        if (remote.isEpub && !opensAsImagePages) return

        if (publicationChanged || pageCountChanged) {
            val refreshedPages = try {
                pageLoader.refreshPages()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                logcat(LogPriority.WARN, error) {
                    "MangaStartup: failed to refresh pages chapterId=$chapterId " +
                        "publicationChanged=$publicationChanged pageCountChanged=$pageCountChanged"
                }
                return
            }
            if (refreshedPages.isNullOrEmpty() && !remote.requiresPageMappingConfirmation) {
                logcat(LogPriority.WARN) {
                    "MangaStartup: page list refresh unavailable chapterId=$chapterId " +
                        "publicationChanged=$publicationChanged pageCountChanged=$pageCountChanged"
                }
                return
            }
            checkNotNull(readerChapter.progressSession).checkCurrent()
            if (!refreshedPages.isNullOrEmpty()) {
                readerChapter.state = ReaderChapter.State.Loaded(refreshedPages)
                pages = refreshedPages
            }
            if (!beforePageActivation && !refreshedPages.isNullOrEmpty()) {
                val activePage = pages[readerChapter.requestedPage.coerceIn(0, pages.lastIndex)]
                pageLoader.setActivePage(activePage)
                eventChannel.trySend(Event.ReloadViewerChapters)
            }
        }

        if (remote.totalPages > 0 && remote.totalPages != pages.size && !remote.requiresPageMappingConfirmation) {
            logcat(LogPriority.WARN) {
                "MangaStartup: server/local page count still differs chapterId=$chapterId " +
                    "server=${remote.totalPages} local=${pages.size}"
            }
            return
        }
        if (!beforePageActivation && getCurrentChapter() !== readerChapter) return

        val legacyEpubProgress = if (opensAsImagePages && remote.pageIndex == null && !remote.completed) {
            runCatching {
                checkNotNull(readerChapter.progressSession).epub?.pullEpubProgress(remote.resourceId)
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "MangaStartup: failed to read legacy remote EPUB progression chapterId=$chapterId"
                }
            }.getOrNull()
        } else {
            null
        }
        checkNotNull(readerChapter.progressSession).checkCurrent()
        val remotePageIndex = remote.pageIndex
            ?: if (remote.completed && pages.isNotEmpty()) {
                pages.lastIndex
            } else {
                EpubPageProgress.pageIndex(
                    progression = legacyEpubProgress?.locator?.locations?.totalProgression,
                    totalPages = pages.size,
                ) ?: 0
            }
        if (remotePageIndex !in pages.indices && !remote.requiresPageMappingConfirmation) return
        val localPageIndex = readerChapter.requestedPage.coerceIn(0, pages.lastIndex)
        val openingLocalPageIndex = synchronized(remoteProgressOpeningPages) {
            remoteProgressOpeningPages[chapterId]
        }?.coerceIn(0, pages.lastIndex) ?: localPageIndex
        val migratesLegacyEpubProgress = legacyEpubProgress != null
        val remoteUpdatedAtMillis = remote.readDate?.toRemoteProgressTimestamp()
            ?: legacyEpubProgress?.modifiedAt?.time
        when (
            RemoteProgressConflictPolicy.decide(
                // History timestamps track reading sessions, not page changes, so they cannot
                // safely prove that a different local page is newer than Komga's position.
                localUpdatedAtMillis = null,
                remoteUpdatedAtMillis = remoteUpdatedAtMillis,
                sameLocation = remotePageIndex == localPageIndex && !remote.requiresPageMappingConfirmation &&
                    !remote.blocksAutomaticSelection,
                localChangedDuringCheck =
                !remote.requiresPageMappingConfirmation &&
                    (localPageIndex != openingLocalPageIndex || chapterId == explicitOpeningChapterId),
                requiresConfirmation = remote.blocksAutomaticSelection,
            )
        ) {
            RemoteProgressDecision.SAME_LOCATION -> {
                if (!confirmPageComparison(readerChapter, remote, localPageIndex, pages.size)) {
                    retryProgressComparison(readerChapter)
                    return
                }
                allowRemoteProgressWrites(chapterId)
                if (!beforePageActivation && migratesLegacyEpubProgress) {
                    pushPageProgressIfAllowed(readerChapter, localPageIndex, pages.size)
                }
                return
            }
            RemoteProgressDecision.KEEP_LOCAL -> {
                if (!confirmPageComparison(readerChapter, remote, localPageIndex, pages.size)) {
                    retryProgressComparison(readerChapter)
                    return
                }
                allowRemoteProgressWrites(chapterId)
                if (!beforePageActivation) {
                    pushPageProgressIfAllowed(readerChapter, localPageIndex, pages.size)
                }
                return
            }
            RemoteProgressDecision.KEEP_REMOTE -> Unit
        }

        val remoteVersion = listOf(
            chapterId,
            remoteUpdatedAtMillis?.toString().orEmpty(),
            remotePageIndex,
            remote.totalPages.takeIf { it > 0 } ?: pages.size,
            remote.completed,
            if (migratesLegacyEpubProgress) "legacy-epub" else "page",
            remote.comparisonVersion.orEmpty(),
        ).joinToString(separator = ":")
        val shouldShow = synchronized(remoteProgressVersionsHandled) {
            remoteProgressVersionsHandled.add(remoteVersion)
        }
        if (!shouldShow) return

        val conflict = MangaRemoteProgressConflict(
            snapshot = remote,
            chapterId = chapterId,
            localPageIndex = localPageIndex,
            localTotalPages = pages.size,
            remotePageIndex = remotePageIndex,
            remoteTotalPages = remote.totalPages.takeIf { count -> count > 0 } ?: pages.size,
            remoteVersion = remoteVersion,
            migratesLegacyEpubProgress = migratesLegacyEpubProgress,
            remoteReadAt = remoteUpdatedAtMillis,
            remoteCompleted = remote.completed,
            requiresPageMappingConfirmation = remote.requiresPageMappingConfirmation,
            canUseRemotePosition = remotePageIndex in pages.indices,
        )
        when {
            remote.requiresConfirmation -> {
                mutableState.update { it.copy(remoteProgressConflict = conflict) }
            }
            beforePageActivation -> applyRemoteProgressBeforePageActivation(readerChapter, pages, conflict)
            else -> {
                mutableState.update { it.copy(remoteProgressConflict = conflict) }
                withUIContext { useRemoteProgress() }
            }
        }
    }

    private suspend fun applyRemoteProgressBeforePageActivation(
        readerChapter: ReaderChapter,
        pages: List<ReaderPage>,
        conflict: MangaRemoteProgressConflict,
    ): Boolean = progressTasks.run {
        applyRemoteProgressInOrder(readerChapter, pages, conflict)
    }

    private suspend fun confirmPageComparison(
        readerChapter: ReaderChapter,
        snapshot: koharia.connection.ConnectionPageProgressSnapshot,
        pageIndex: Int,
        totalPages: Int,
    ) = progressTasks.run {
        if (incognitoMode) return@run true
        readerProgressAction {
            connectionPageProgressAdapter(readerChapter)?.confirmPageComparison(
                readerChapter.chapter.url,
                snapshot,
                pageIndex,
                totalPages,
                System.currentTimeMillis(),
                koharia.connection.ConnectionPageProgressDecision.COMPARED,
            ) {}
        }
    }

    private suspend fun applyRemoteProgressInOrder(
        readerChapter: ReaderChapter,
        pages: List<ReaderPage>,
        conflict: MangaRemoteProgressConflict,
    ): Boolean {
        val targetPage = pages.getOrNull(conflict.remotePageIndex) ?: return false
        val completed = conflict.remoteCompleted ?: (conflict.remotePageIndex == pages.lastIndex)
        if (!readerProgressAction {
                checkNotNull(readerChapter.progressSession).checkCurrent()
                if (!incognitoMode) {
                    val commitChapter: suspend () -> Unit = {
                        persistReaderChapter(
                            readerChapter,
                            ChapterUpdate(
                                id = conflict.chapterId,
                                read = completed,
                                lastPageRead = targetPage.index.toLong(),
                            ),
                        )
                    }
                    val progress = connectionPageProgressAdapter(readerChapter)
                    if (progress == null) {
                        commitChapter()
                    } else {
                        progress.confirmPageComparison(
                            readerChapter.chapter.url,
                            conflict.snapshot,
                            targetPage.index,
                            pages.size,
                            conflict.remoteReadAt ?: 0L,
                            koharia.connection.ConnectionPageProgressDecision.USE_REMOTE,
                            commitChapter,
                        )
                    }
                }
            }
        ) {
            return false
        }
        readerChapter.requestedPage = targetPage.index
        readerChapter.chapter.last_page_read = targetPage.index
        readerChapter.chapter.read = completed
        chapterPageIndex = targetPage.index
        synchronized(remoteProgressOpeningPages) { remoteProgressOpeningPages[conflict.chapterId] = targetPage.index }
        allowRemoteProgressWrites(conflict.chapterId)
        return true
    }

    private suspend fun restoreDivinaEpubPage(readerChapter: ReaderChapter): Int? {
        val chapter = readerChapter.chapter
        val publicationAdapter = connectionPublicationAdapter() ?: return null
        if (!publicationAdapter.canOpenAsPages(chapter.memo) ||
            publicationAdapter.hasMigratedPageProgress(chapter.memo)
        ) {
            return null
        }
        val chapterId = chapter.id ?: return null
        val totalPages = publicationAdapter.pageCountFromMemo(chapter.memo) ?: return null
        val migratedPage = if (chapter.last_page_read > 0) {
            null
        } else {
            EpubPageProgress.pageIndex(getEpubProgress.await(chapterId)?.progression, totalPages)
        }
        if (!incognitoMode) {
            val migratedMemo = publicationAdapter.markPageProgressMigrated(chapter.memo)
            persistReaderChapter(
                readerChapter,
                ChapterUpdate(
                    id = chapterId,
                    lastPageRead = migratedPage?.toLong(),
                    memo = migratedMemo,
                ),
            )
            chapter.memo = migratedMemo
            migratedPage?.let { chapter.last_page_read = it }
        }
        return migratedPage
    }

    private fun isDivinaEpub(readerChapter: ReaderChapter): Boolean {
        return connectionPublicationAdapter()?.canOpenAsPages(readerChapter.chapter.memo) == true
    }

    private fun connectionPublicationAdapter(): ConnectionPublicationAdapter? {
        return readingSource as? ConnectionPublicationAdapter
    }

    private suspend fun bindProgressSession(readerChapter: ReaderChapter) =
        readerChapter.progressSessionMutex.withLock {
            readerChapter.progressSession ?: eu.kanade.tachiyomi.ui.reader.model.ReaderProgressSession.capture(
                readingSource,
                checkNotNull(manga).source,
                checkNotNull(readerChapter.chapter.toDomainChapter()),
                sourceManager,
            ).also { readerChapter.progressSession = it }
        }

    private fun connectionPageProgressAdapter(readerChapter: ReaderChapter) =
        checkNotNull(readerChapter.progressSession) { "Reader progress session has not been captured" }.pages

    private suspend fun readerProgressAction(operation: suspend () -> Unit): Boolean = try {
        operation()
        true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        logcat(LogPriority.WARN, error) { "Reader progress operation failed" }
        withUIContext { Injekt.get<Application>().toast(MR.strings.epub_reader_progress_save_failed) }
        false
    }

    private suspend fun persistReaderChapter(readerChapter: ReaderChapter, update: ChapterUpdate) {
        if (koharia.connection.DevReadOnlyValidation.enabled) return
        val session = checkNotNull(readerChapter.progressSession)
        updateChapter.awaitForReader(listOf(session.owner to update), session.checkCurrent)
    }

    private fun allowRemoteProgressWrites(chapterId: Long) {
        synchronized(remoteProgressWritesAllowed) {
            remoteProgressWritesAllowed.add(chapterId)
        }
    }

    private fun canWriteRemoteProgress(chapterId: Long): Boolean =
        synchronized(remoteProgressWritesAllowed) {
            chapterId in remoteProgressWritesAllowed
        }

    private suspend fun pushPageProgressIfAllowed(
        readerChapter: ReaderChapter,
        pageIndex: Int,
        totalPages: Int,
    ) {
        if (koharia.connection.DevReadOnlyValidation.enabled ||
            readerChapter.pageLoader?.supportsRemoteProgress == false
        ) {
            return
        }
        val chapterId = readerChapter.chapter.id ?: return
        if (!canWriteRemoteProgress(chapterId)) {
            logcat(LogPriority.DEBUG) {
                "Skipping remote page progress write before successful comparison chapterId=$chapterId"
            }
            return
        }
        connectionPageProgressAdapter(readerChapter)?.pushPageProgress(
            chapterUrl = readerChapter.chapter.url,
            pageIndex = pageIndex,
            totalPages = totalPages,
        )
    }

    fun keepLocalProgress() {
        val conflict = state.value.remoteProgressConflict ?: return
        val currentChapter = getCurrentChapter()?.takeIf { it.chapter.id == conflict.chapterId } ?: return
        val pages = currentChapter.pages ?: return
        val localPageIndex = currentChapter.requestedPage.coerceIn(0, pages.lastIndex)
        val selectedAt = System.currentTimeMillis()
        progressTasks.submit {
            val accepted = readerProgressAction {
                checkNotNull(currentChapter.progressSession).checkCurrent()
                connectionPageProgressAdapter(currentChapter).takeUnless { incognitoMode }
                    ?.confirmPageComparison(
                        currentChapter.chapter.url,
                        conflict.snapshot,
                        localPageIndex,
                        pages.size,
                        selectedAt,
                        koharia.connection.ConnectionPageProgressDecision.KEEP_LOCAL,
                    ) {}
                checkNotNull(currentChapter.progressSession).checkCurrent()
                synchronized(remoteProgressOpeningPages) {
                    remoteProgressOpeningPages[conflict.chapterId] =
                        localPageIndex
                }
                allowRemoteProgressWrites(conflict.chapterId)
                navigatedConnectionChapters.add(conflict.chapterId)
                mutableState.update {
                    if (it.remoteProgressConflict ==
                        conflict
                    ) {
                        it.copy(remoteProgressConflict = null)
                    } else {
                        it
                    }
                }
                pushPageProgressIfAllowed(currentChapter, localPageIndex, pages.size)
            }
            if (!accepted) retryProgressComparison(currentChapter)
        }
    }

    private fun retryProgressComparison(readerChapter: ReaderChapter) {
        val manga = manga ?: return
        val adapter = connectionPageProgressAdapter(readerChapter) ?: return
        scheduleRemoteProgressRetry(adapter, manga, readerChapter)
    }

    fun useRemoteProgress() {
        val conflict = state.value.remoteProgressConflict ?: return
        if (!conflict.canUseRemotePosition) return
        val currentChapter = getCurrentChapter()?.takeIf { it.chapter.id == conflict.chapterId } ?: return
        val pages = currentChapter.pages ?: return
        val targetPage = pages.getOrNull(conflict.remotePageIndex) ?: return
        viewModelScope.launchNonCancellable {
            if (!applyRemoteProgressBeforePageActivation(currentChapter, pages, conflict)) {
                retryProgressComparison(currentChapter)
                return@launchNonCancellable
            }
            withUIContext {
                if (getCurrentChapter() === currentChapter) {
                    currentChapter.pageLoader?.setActivePage(targetPage)
                    state.value.viewer?.restorePage(targetPage)
                }
                mutableState.update {
                    if (it.remoteProgressConflict ==
                        conflict
                    ) {
                        it.copy(remoteProgressConflict = null)
                    } else {
                        it
                    }
                }
            }
            if (!incognitoMode && conflict.migratesLegacyEpubProgress) {
                readerProgressAction { pushPageProgressIfAllowed(currentChapter, targetPage.index, pages.size) }
            }
        }
    }

    private fun downloadNextChapters() {
        if (koharia.connection.DevReadOnlyValidation.enabled) return
        if (downloadAheadAmount == 0) return
        val manga = manga ?: return

        // Only download ahead if current + next chapter is already downloaded too to avoid jank
        if (getCurrentChapter()?.pageLoader !is DownloadPageLoader) return
        val nextChapter = state.value.viewerChapters?.nextChapter?.chapter ?: return

        viewModelScope.launchIO {
            val isNextChapterDownloaded = downloadManager.isChapterDownloaded(
                nextChapter.name,
                nextChapter.scanlator,
                nextChapter.url,
                manga.title,
                manga.source,
            )
            if (!isNextChapterDownloaded) return@launchIO

            val chaptersToDownload = getNextChapters.await(manga.id, nextChapter.id!!).run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(nextChapter.toDomainChapter()!!)
                } else {
                    this
                }
            }.take(downloadAheadAmount)

            downloadManager.cacheChaptersForOffline(
                manga,
                chaptersToDownload,
            )
        }
    }

    private fun cacheCurrentChapterForOffline(currentChapter: ReaderChapter) {
        if (!cacheCurrentChapterWhileReading) return
        if (currentChapter.pageLoader is DownloadPageLoader) return

        val manga = manga ?: return
        val chapter = currentChapter.chapter.toDomainChapter() ?: return
        if ((sourceManager.get(manga.source) as? ConnectionRawDownloadAdapter)?.preferRawDownload(chapter) ==
            true
        ) {
            return
        }
        val chapterId = chapter.id
        synchronized(currentChapterAutoCacheRequests) {
            if (!currentChapterAutoCacheRequests.add(chapterId)) return
        }

        viewModelScope.launchIO {
            val isDownloaded = downloadManager.isChapterDownloaded(
                chapter.name,
                chapter.scanlator,
                chapter.url,
                manga.title,
                manga.source,
                skipCache = true,
            )
            if (isDownloaded || downloadManager.getQueuedDownloadOrNull(chapterId) != null) {
                return@launchIO
            }

            downloadManager.cacheChapterPagesForOffline(manga, chapter)
        }
    }

    /**
     * Removes [currentChapter] from download queue
     * if setting is enabled and [currentChapter] is queued for download
     */
    private fun cancelQueuedDownloads(currentChapter: ReaderChapter): Download? {
        if (koharia.connection.DevReadOnlyValidation.enabled) return null
        return downloadManager.getQueuedDownloadOrNull(currentChapter.chapter.id!!)?.also {
            downloadManager.cancelQueuedDownloads(listOf(it))
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last chapter actually exists.
     * If both conditions are satisfied enqueues chapter for delete
     * @param currentChapter current chapter, which is going to be marked as read.
     */
    private suspend fun deleteChapterIfNeeded(currentChapter: ReaderChapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots.get()
        if (removeAfterReadSlots == -1) return

        // Determine which chapter should be deleted and enqueue
        val currentChapterPosition = chapterList.indexOf(currentChapter)
        val chapterToDelete = chapterList.getOrNull(currentChapterPosition - removeAfterReadSlots)

        // If chapter is completely read, no need to download it
        chapterToDownload = null

        if (chapterToDelete != null) {
            enqueueDeleteReadChapters(chapterToDelete, checkNotNull(currentChapter.progressSession).checkCurrent)
        }
    }

    /**
     * Saves the chapter progress (last read page and whether it's read)
     * if incognito mode isn't on.
     */
    private suspend fun updateChapterProgress(
        readerChapter: ReaderChapter,
        page: Page,
        confirmed: Boolean = false,
        commitLocal: (suspend (suspend () -> Unit) -> Unit)? = null,
    ) {
        val pageIndex = page.index

        if (incognitoMode || page.status is Page.State.Error) return
        val session = checkNotNull(readerChapter.progressSession)
        val completed = readerChapter.pages?.lastIndex == pageIndex
        val read = readerChapter.chapter.read || completed
        val updates = mutableListOf(
            session.owner to ChapterUpdate(
                id = session.owner.chapterId,
                read = read,
                lastPageRead = pageIndex.toLong(),
            ),
        )
        val guards = mutableListOf(session.checkCurrent)
        if (completed && libraryPreferences.markDuplicateReadChapterAsRead.get()
                .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        ) {
            val duplicates = unfilteredChapterList.filter {
                it.id != session.owner.chapterId && !it.read && it.isRecognizedNumber &&
                    it.chapterNumber.toFloat() == readerChapter.chapter.chapter_number
            }
            for (duplicate in duplicates) {
                val duplicateSession = eu.kanade.tachiyomi.ui.reader.model.ReaderProgressSession.capture(
                    readingSource,
                    session.owner.sourceId,
                    duplicate,
                    sourceManager,
                )
                updates += duplicateSession.owner to ChapterUpdate(id = duplicate.id, read = true)
                guards += duplicateSession.checkCurrent
            }
        }
        val commitChapter: suspend () -> Unit = { updateChapter.awaitForReader(updates) { guards.forEach { it() } } }
        if (commitLocal == null) commitChapter() else commitLocal(commitChapter)
        readerChapter.chapter.last_page_read = pageIndex
        readerChapter.chapter.read = read
        if (completed && confirmed) {
            session.checkCurrent()
            updateTrackChapterRead(readerChapter)
            session.checkCurrent()
            deleteChapterIfNeeded(readerChapter)
            pushPageProgressIfAllowed(readerChapter, pageIndex, readerChapter.pages?.size ?: 0)
        }
    }

    fun restartReadTimer() {
        chapterReadStartTime = Instant.now().toEpochMilli()
    }

    /**
     * Saves the chapter last read history if incognito mode isn't on.
     */
    suspend fun updateHistory() {
        val historyChapter = getCurrentChapter()
        val historyEnd = Date()
        progressTasks.run { updateHistoryInOrder(historyChapter, historyEnd) }
    }

    private suspend fun updateHistoryInOrder(historyChapter: ReaderChapter?, endTime: Date) {
        readerProgressAction {
            historyChapter?.let { readerChapter ->
                if (incognitoMode) return@let

                checkNotNull(readerChapter.progressSession).checkCurrent()
                val chapterId = readerChapter.chapter.id!!
                val timerStart = chapterReadStartTime?.takeIf { it <= endTime.time }
                val sessionReadDuration = timerStart?.let { endTime.time - it } ?: 0

                if (sessionReadDuration > 0) {
                    val confirmedPage = confirmedConnectionPages[chapterId]
                    if (confirmedPage != null && state.value.remoteProgressConflict == null) {
                        runCatching {
                            (
                                connectionPageProgressAdapter(
                                    readerChapter,
                                ) as? koharia.connection.ConnectionLocalPageProgressAdapter
                                )
                                ?.recordLocalPageProgress(
                                    readerChapter.chapter.url,
                                    confirmedPage,
                                    readerChapter.pages?.size ?: 0,
                                    endTime.time,
                                    initialPage =
                                    chapterId !in navigatedConnectionChapters && chapterId != explicitOpeningChapterId,
                                )
                        }.onFailure { error ->
                            logcat(LogPriority.WARN, error) { "Failed to persist connection reading on exit" }
                        }
                    }
                    pushPageProgressIfAllowed(
                        readerChapter = readerChapter,
                        pageIndex = readerChapter.chapter.last_page_read,
                        totalPages = readerChapter.pages?.size ?: 0,
                    )
                }

                checkNotNull(readerChapter.progressSession).checkCurrent()
                upsertHistory.await(HistoryUpdate(chapterId, endTime, sessionReadDuration))
                if (chapterReadStartTime == timerStart) chapterReadStartTime = null
            }
        }
    }

    /**
     * Called from the activity to load and set the next chapter as active.
     */
    suspend fun loadNextChapter() {
        val nextChapter = state.value.viewerChapters?.nextChapter ?: return
        loadAdjacent(nextChapter)
    }

    /**
     * Called from the activity to load and set the previous chapter as active.
     */
    suspend fun loadPreviousChapter() {
        val prevChapter = state.value.viewerChapters?.prevChapter ?: return
        loadAdjacent(prevChapter)
    }

    /**
     * Returns the currently active chapter.
     */
    private fun getCurrentChapter(): ReaderChapter? {
        return state.value.currentChapter
    }

    fun getSource() = manga?.source?.let { sourceManager.getOrStub(it) } as? HttpSource

    fun getChapterUrl(): String? {
        val sChapter = getCurrentChapter()?.chapter ?: return null
        val source = getSource() ?: return null

        return try {
            source.getChapterUrl(sChapter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    /**
     * Bookmarks the currently active chapter.
     */
    fun toggleChapterBookmark() {
        val readerChapter = getCurrentChapter() ?: return
        val chapter = readerChapter.chapter
        val bookmarked = !chapter.bookmark
        viewModelScope.launchNonCancellable {
            if (readerProgressAction {
                    persistReaderChapter(readerChapter, ChapterUpdate(id = chapter.id!!, bookmark = bookmarked))
                }
            ) {
                chapter.bookmark = bookmarked
                mutableState.update {
                    if (it.currentChapter ===
                        readerChapter
                    ) {
                        it.copy(bookmarked = bookmarked)
                    } else {
                        it
                    }
                }
            }
        }
    }

    /**
     * Returns the viewer position used by this manga or the default one.
     */
    fun getMangaReadingMode(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultReadingMode.get()
        val readingMode = ReadingMode.fromPreference(manga?.readingMode?.toInt())
        return when {
            resolveDefault && readingMode == ReadingMode.DEFAULT -> default
            else -> manga?.readingMode?.toInt() ?: default
        }
    }

    /**
     * Updates the viewer position for the open manga.
     */
    fun setMangaReadingMode(readingMode: ReadingMode) {
        val manga = manga ?: return
        runBlocking(Dispatchers.IO) {
            setMangaViewerFlags.awaitSetReadingMode(manga.id, readingMode.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    /**
     * Returns the orientation type used by this manga or the default one.
     */
    fun getMangaOrientation(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultOrientationType.get()
        val orientation = ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt())
        return when {
            resolveDefault && orientation == ReaderOrientation.DEFAULT -> default
            else -> manga?.readerOrientation?.toInt() ?: default
        }
    }

    /**
     * Updates the orientation type for the open manga.
     */
    fun setMangaOrientationType(orientation: ReaderOrientation) {
        val manga = manga ?: return
        viewModelScope.launchIO {
            setMangaViewerFlags.awaitSetOrientation(manga.id, orientation.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.SetOrientation(getMangaOrientation()))
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    fun toggleCropBorders(): Boolean {
        val isPagerType = ReadingMode.isPagerType(getMangaReadingMode())
        return if (isPagerType) {
            readerPreferences.cropBorders.toggle()
        } else {
            readerPreferences.cropBordersWebtoon.toggle()
        }
    }

    /**
     * Generate a filename for the given [manga] and [page]
     */
    private fun generateFilename(
        manga: Manga,
        page: ReaderPage,
        extraSuffix: String = "",
    ): String {
        val chapter = page.chapter.chapter
        val filenameSuffix = " - ${page.number}$extraSuffix"
        return DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            DiskUtil.MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    fun showLoadingDialog() {
        mutableState.update { it.copy(dialog = Dialog.Loading) }
    }

    fun openReadingModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.ReadingModeSelect) }
    }

    fun openOrientationModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.OrientationModeSelect) }
    }

    fun openPageDialog(page: ReaderPage, mergedPages: List<ReaderPage>? = null) {
        mutableState.update { it.copy(dialog = Dialog.PageActions(page, mergedPages)) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun setBrightnessOverlayValue(value: Int) {
        mutableState.update { it.copy(brightnessOverlayValue = value) }
    }

    /**
     * Saves the image of the selected page on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage() {
        val action = state.value.dialog as? Dialog.PageActions ?: return
        val page = action.page.takeIf { it.status == Page.State.Ready } ?: return
        val spread = action.mergedPages?.takeIf { pages ->
            pages.size == 2 && pages.all { it.status == Page.State.Ready && it.stream != null }
        }
        val preferences = Injekt.get<koharia.connection.SharedAppPreferences>().readerPreferences()
        val merged = spread != null && preferences.doublePageSaveMode.get() == DoublePageSaveMode.MERGED
        val options = MergedPageExportOptions(preferences.mergedPageLayout.get(), preferences.mergedPageFormat.get())
        val targets = if (merged) listOf(checkNotNull(spread).minBy { it.index }) else spread ?: listOf(page)
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        // Pictures directory.
        val relativePath = if (readerPreferences.folderPerManga.get()) {
            DiskUtil.buildValidFilename(
                manga.title,
            )
        } else {
            ""
        }

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                var savedUri: Uri? = null
                for (target in targets) {
                    var temporaryFile: File? = null
                    try {
                        val filename = if (merged) {
                            generateFilename(manga, target, "-${checkNotNull(spread).maxOf { it.number }}-merged")
                        } else {
                            generateFilename(manga, target)
                        }
                        val input = if (merged) {
                            val file = File.createTempFile("merged-page-", ".image", context.cacheDir)
                            temporaryFile = file
                            val pages = checkNotNull(spread)
                            MergedPageImage.write(
                                checkNotNull(pages[0].stream),
                                checkNotNull(pages[1].stream),
                                file,
                                options,
                            ).let { export -> { export.file.inputStream() } }
                        } else {
                            target.stream
                                ?: File.createTempFile("page-export-", ".png", context.cacheDir).also { file ->
                                    temporaryFile = file
                                    writeReaderPageBitmap(target, file)
                                }.let { file -> { file.inputStream() } }
                        }
                        savedUri = imageSaver.save(
                            Image.Page(
                                inputStream = input,
                                name = filename,
                                location = Location.Pictures.create(relativePath),
                            ),
                        )
                    } finally {
                        temporaryFile?.delete()
                    }
                }
                val uri = checkNotNull(savedUri)
                withUIContext {
                    notifier.onComplete(uri)
                    eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
                }
            } catch (e: Throwable) {
                val error = if (merged) {
                    IllegalStateException(
                        context.stringResource(
                            if (e is MergedPageImage.InsufficientMemoryException) {
                                MR.strings.save_merged_page_insufficient_memory
                            } else {
                                MR.strings.error_saving_picture
                            },
                        ),
                        e,
                    )
                } else {
                    e
                }
                notifier.onError(error.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(error)))
            }
        }
    }

    /**
     * Shares the image of the selected page and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(copyToClipboard: Boolean) {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val filename = generateFilename(manga, page)

        viewModelScope.launchNonCancellable {
            var renderedFile: File? = null
            try {
                val input = page.stream ?: File.createTempFile("page-share-", ".png", context.cacheDir).also { file ->
                    renderedFile = file
                    writeReaderPageBitmap(page, file)
                }.let { file -> { file.inputStream() } }
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = input,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, page))
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            } finally {
                renderedFile?.delete()
            }
        }
    }

    /**
     * Sets the image of the selected page as cover and notifies the UI of the result.
     */
    fun setAsCover() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return
        val stream = page.stream ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                manga.editCover(stream(), sourceManager = sourceManager)
                if (manga.isConnectionLibraryEntry(sourceManager)) {
                    SetAsCoverResult.Success
                } else {
                    SetAsCoverResult.AddToLibraryFirst
                }
            } catch (e: Exception) {
                SetAsCoverResult.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    enum class SetAsCoverResult {
        Success,
        AddToLibraryFirst,
        Error,
    }

    sealed interface SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult
        class Error(val error: Throwable) : SaveImageResult
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private suspend fun updateTrackChapterRead(readerChapter: ReaderChapter) {
        if (incognitoMode) return
        if (!trackPreferences.autoUpdateTrack.get()) return

        val manga = manga ?: return
        val context = Injekt.get<Application>()

        checkNotNull(readerChapter.progressSession).checkCurrent()
        trackChapter.await(context, manga.id, readerChapter.chapter.chapter_number.toDouble())
    }

    /**
     * Enqueues this [chapter] to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting it across process deaths.
     */
    private suspend fun enqueueDeleteReadChapters(chapter: ReaderChapter, checkCurrent: suspend () -> Unit) {
        if (!chapter.chapter.read) return
        val manga = manga ?: return

        checkCurrent()
        downloadManager.enqueueChaptersToDelete(listOf(chapter.chapter.toDomainChapter()!!), manga)
    }

    /**
     * Deletes all the pending chapters. This operation will run in a background thread and errors
     * are ignored.
     */
    private fun deletePendingChapters() {
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingChapters()
        }
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        val viewerChapters: ViewerChapters? = null,
        val bookmarked: Boolean = false,
        val isLoadingAdjacentChapter: Boolean = false,
        val archiveLoadingStage: eu.kanade.tachiyomi.ui.reader.loader.ArchiveLoadingStage? = null,
        val currentPage: Int = -1,

        /**
         * Viewer used to display the pages (pager, webtoon, ...).
         */
        val viewer: Viewer? = null,
        val dialog: Dialog? = null,
        val remoteProgressConflict: MangaRemoteProgressConflict? = null,
        val menuVisible: Boolean = false,
        val visiblePageStart: Int = -1,
        val visiblePageEnd: Int = -1,
        @IntRange(from = -100, to = 100) val brightnessOverlayValue: Int = 0,
    ) {
        val currentChapter: ReaderChapter?
            get() = viewerChapters?.currChapter

        val totalPages: Int
            get() = currentChapter?.pages?.size ?: -1
    }

    sealed interface Dialog {
        data object Loading : Dialog
        data object ReadingModeSelect : Dialog
        data object OrientationModeSelect : Dialog
        data class PageActions(val page: ReaderPage, val mergedPages: List<ReaderPage>? = null) : Dialog
    }

    sealed interface Event {
        data object ReloadViewerChapters : Event
        data class DocumentPagesRefreshed(val page: ReaderPage) : Event
        data object PageChanged : Event
        data class SetOrientation(val orientation: Int) : Event
        data class SetCoverResult(val result: SetAsCoverResult) : Event

        data class SavedImage(val result: SaveImageResult) : Event
        data class ShareImage(val uri: Uri, val page: ReaderPage) : Event
        data class CopyImage(val uri: Uri) : Event
    }
}

private fun String.toRemoteProgressTimestamp(): Long? =
    runCatching { Instant.parse(this).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }
        .getOrNull()

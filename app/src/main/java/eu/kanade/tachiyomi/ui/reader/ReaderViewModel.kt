package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.net.Uri
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
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.data.track.komga.KomgaProgressSyncService
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.util.chapter.filterDownloaded
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import koharia.domain.epub.interactor.GetEpubProgress
import koharia.epub.progress.EpubPageProgress
import koharia.epub.progress.KomgaEpubProgressSyncService
import koharia.komga.download.KomgaChapterMemo
import koharia.source.komga.KomgaScopedPreferenceStoreFactory
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.util.Date

/**
 * Presenter used by the activity to perform background operations.
 */
class ReaderViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val imageSaver: ImageSaver = Injekt.get(),
    globalReaderPreferences: ReaderPreferences = Injekt.get(),
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
    private val komgaProgressSyncService: KomgaProgressSyncService = Injekt.get(),
    private val komgaEpubProgressSyncService: KomgaEpubProgressSyncService = Injekt.get(),
    private val getEpubProgress: GetEpubProgress = Injekt.get(),
    private val scopedPreferenceStoreFactory: KomgaScopedPreferenceStoreFactory = Injekt.get(),
) : ViewModel() {

    val readerPreferences: ReaderPreferences =
        scopedPreferenceStoreFactory.readerPreferencesForSavedSource(savedState) ?: globalReaderPreferences
    private val basePreferences: BasePreferences =
        scopedPreferenceStoreFactory.basePreferencesForSavedSource(savedState) ?: globalBasePreferences
    private val downloadPreferences: DownloadPreferences =
        scopedPreferenceStoreFactory.downloadPreferencesForSavedSource(savedState) ?: globalDownloadPreferences
    private val trackPreferences: TrackPreferences =
        scopedPreferenceStoreFactory.trackPreferencesForSavedSource(savedState) ?: globalTrackPreferences
    private val libraryPreferences: LibraryPreferences =
        scopedPreferenceStoreFactory.libraryPreferencesForSavedSource(savedState) ?: globalLibraryPreferences

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

    /**
     * The time the chapter was started reading
     */
    private var chapterReadStartTime: Long? = null

    private var chapterToDownload: Download? = null
    private val currentChapterAutoCacheRequests = mutableSetOf<Long>()
    private val remoteProgressChecksStarted = mutableSetOf<Long>()
    private val remoteProgressVersionsHandled = mutableSetOf<String>()

    private val unfilteredChapterList by lazy {
        val manga = manga!!
        runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = false) }
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
                if (basePreferences.downloadedOnly.get()) {
                    filterDownloaded(manga)
                } else {
                    this
                }
            }
            .map { it.toDbChapter() }
            .map(::ReaderChapter)
    }

    private val incognitoMode: Boolean by lazy { basePreferences.incognitoMode.get() }
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading.get()
    private val cacheCurrentChapterWhileReading = downloadPreferences.cacheCurrentChapterWhileReading.get()

    init {
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
                cacheCurrentChapterForOffline(currentChapter)
            }
            .launchIn(viewModelScope)
    }

    override fun onCleared() {
        val currentChapters = state.value.viewerChapters
        if (currentChapters != null) {
            currentChapters.unref()
            chapterToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
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
                    val source = sourceManager.getOrStub(manga.source)
                    loader = ChapterLoader(context, downloadManager, downloadProvider, manga, source)

                    val initialChapter = chapterList.first { chapterId == it.chapter.id }
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
        loader.loadChapter(chapter, initialPageIndex)

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

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    suspend fun preload(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded || chapter.state == ReaderChapter.State.Loading) {
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

        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) {
            return
        }

        val loader = loader ?: return
        try {
            logcat { "Preloading ${chapter.chapter.url}" }
            loader.loadChapter(chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return
        }
        eventChannel.trySend(Event.ReloadViewerChapters)
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

        val selectedChapter = page.chapter
        val pages = selectedChapter.pages ?: return
        selectedChapter.requestedPage = page.index
        chapterPageIndex = page.index
        selectedChapter.pageLoader?.setActivePage(page)

        // Save last page read and mark as read if needed
        viewModelScope.launchNonCancellable {
            updateChapterProgress(selectedChapter, page)
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

    fun onPageDisplayed(page: ReaderPage) {
        page.chapter.pageLoader?.onPageDisplayed(page)
        val currentChapter = getCurrentChapter() ?: return
        if (currentChapter !== page.chapter || currentChapter.requestedPage != page.index) return
        logcat {
            "MangaStartup: active image displayed chapterId=${page.chapter.chapter.id} page=${page.number}"
        }
        if (incognitoMode) return
        val manga = manga ?: return
        if (sourceManager.get(manga.source) !is KomgaSource) return
        val currentChapterId = currentChapter.chapter.id ?: return
        if (!remoteProgressChecksStarted.add(currentChapterId)) return

        viewModelScope.launchIO {
            refreshKomgaBookProgress(manga, currentChapter)
        }
    }

    private suspend fun refreshKomgaBookProgress(manga: Manga, readerChapter: ReaderChapter) {
        val chapter = readerChapter.chapter
        val chapterId = chapter.id ?: return
        logcat { "MangaStartup: remote progress check start chapterId=$chapterId" }
        val remote = komgaProgressSyncService.pullBookProgress(
            sourceId = manga.source,
            chapterUrl = chapter.url,
        )
        logcat {
            "MangaStartup: remote progress check complete chapterId=$chapterId " +
                "found=${remote != null}"
        }
        if (remote == null) return

        val oldMemo = chapter.memo
        val oldPublicationVersion = KomgaChapterMemo.publicationVersion(oldMemo)
        val updatedMemo = KomgaChapterMemo.mergePublicationMetadata(
            existing = oldMemo,
            bookUrl = remote.url,
            fileHash = remote.fileHash,
            fileLastModified = remote.fileLastModified,
            sizeBytes = remote.sizeBytes,
            fileName = remote.fileName,
            isEpub = remote.isEpub,
            epubDivinaCompatible = remote.isDivinaCompatibleEpub.takeIf { remote.isEpub },
            pagesCount = remote.totalPages,
        )
        val opensAsImagePages = remote.isEpub && KomgaChapterMemo.canOpenEpubAsPages(updatedMemo)
        val newPublicationVersion = KomgaChapterMemo.publicationVersion(updatedMemo)
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
            chapter.memo = updatedMemo
            updateChapter.await(ChapterUpdate(id = chapterId, memo = updatedMemo))
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
            if (refreshedPages.isNullOrEmpty()) {
                logcat(LogPriority.WARN) {
                    "MangaStartup: page list refresh unavailable chapterId=$chapterId " +
                        "publicationChanged=$publicationChanged pageCountChanged=$pageCountChanged"
                }
                return
            }
            readerChapter.state = ReaderChapter.State.Loaded(refreshedPages)
            pages = refreshedPages
            val activePage = pages[readerChapter.requestedPage.coerceIn(0, pages.lastIndex)]
            pageLoader.setActivePage(activePage)
            eventChannel.trySend(Event.ReloadViewerChapters)
        }

        if (remote.totalPages > 0 && remote.totalPages != pages.size) {
            logcat(LogPriority.WARN) {
                "MangaStartup: server/local page count still differs chapterId=$chapterId " +
                    "server=${remote.totalPages} local=${pages.size}"
            }
            return
        }
        if (getCurrentChapter() !== readerChapter) return

        val legacyEpubProgress = if (opensAsImagePages && remote.pageIndex == null && !remote.completed) {
            runCatching {
                komgaEpubProgressSyncService.pullProgression(manga.source, remote.url).progression
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "MangaStartup: failed to read legacy EPUB progression chapterId=$chapterId"
                }
            }.getOrNull()
        } else {
            null
        }
        val remotePageIndex = remote.pageIndex
            ?: if (remote.completed && pages.isNotEmpty()) {
                pages.lastIndex
            } else {
                EpubPageProgress.pageIndex(
                    progression = legacyEpubProgress?.locator?.locations?.totalProgression,
                    totalPages = pages.size,
                ) ?: return
            }
        if (remotePageIndex !in pages.indices) return
        val localPageIndex = readerChapter.requestedPage.coerceIn(0, pages.lastIndex)
        val migratesLegacyEpubProgress = legacyEpubProgress != null
        if (remotePageIndex == localPageIndex) {
            if (migratesLegacyEpubProgress) {
                komgaProgressSyncService.pushPageProgress(
                    sourceId = manga.source,
                    chapterUrl = chapter.url,
                    pageIndex = localPageIndex,
                    totalPages = pages.size,
                )
            }
            return
        }

        val remoteVersion = listOf(
            chapterId,
            remote.readDate ?: legacyEpubProgress?.modifiedAt?.time?.toString().orEmpty(),
            remotePageIndex,
            remote.totalPages.takeIf { it > 0 } ?: pages.size,
            remote.completed,
            if (migratesLegacyEpubProgress) "legacy-epub" else "page",
        ).joinToString(separator = ":")
        val shouldShow = synchronized(remoteProgressVersionsHandled) {
            remoteProgressVersionsHandled.add(remoteVersion)
        }
        if (!shouldShow) return

        mutableState.update {
            it.copy(
                remoteProgressConflict = MangaRemoteProgressConflict(
                    chapterId = chapterId,
                    localPageIndex = localPageIndex,
                    localTotalPages = pages.size,
                    remotePageIndex = remotePageIndex,
                    remoteTotalPages = remote.totalPages.takeIf { count -> count > 0 } ?: pages.size,
                    remoteVersion = remoteVersion,
                    migratesLegacyEpubProgress = migratesLegacyEpubProgress,
                ),
            )
        }
    }

    private suspend fun restoreDivinaEpubPage(readerChapter: ReaderChapter): Int? {
        val chapter = readerChapter.chapter
        if (!isDivinaEpub(readerChapter) || KomgaChapterMemo.isEpubPageProgressMigrated(chapter.memo)) return null
        val chapterId = chapter.id ?: return null
        val totalPages = KomgaChapterMemo.pagesCount(chapter.memo) ?: return null
        val migratedPage = if (chapter.last_page_read > 0) {
            null
        } else {
            EpubPageProgress.pageIndex(getEpubProgress.await(chapterId)?.progression, totalPages)
        }
        if (!incognitoMode) {
            val migratedMemo = KomgaChapterMemo.markEpubPageProgressMigrated(chapter.memo)
            chapter.memo = migratedMemo
            migratedPage?.let { chapter.last_page_read = it }
            updateChapter.await(
                ChapterUpdate(
                    id = chapterId,
                    lastPageRead = migratedPage?.toLong(),
                    memo = migratedMemo,
                ),
            )
        }
        return migratedPage
    }

    private fun isDivinaEpub(readerChapter: ReaderChapter): Boolean {
        return KomgaChapterMemo.canOpenEpubAsPages(readerChapter.chapter.memo)
    }

    fun keepLocalProgress() {
        val conflict = state.value.remoteProgressConflict ?: return
        mutableState.update { it.copy(remoteProgressConflict = null) }
        val currentChapter = getCurrentChapter()?.takeIf { it.chapter.id == conflict.chapterId } ?: return
        val pages = currentChapter.pages ?: return
        val localPageIndex = currentChapter.requestedPage.coerceIn(0, pages.lastIndex)
        viewModelScope.launchIO {
            komgaProgressSyncService.pushPageProgress(
                sourceId = manga?.source ?: return@launchIO,
                chapterUrl = currentChapter.chapter.url,
                pageIndex = localPageIndex,
                totalPages = pages.size,
            )
        }
    }

    fun useRemoteProgress() {
        val conflict = state.value.remoteProgressConflict ?: return
        mutableState.update { it.copy(remoteProgressConflict = null) }
        val currentChapter = getCurrentChapter()?.takeIf { it.chapter.id == conflict.chapterId } ?: return
        val pages = currentChapter.pages ?: return
        val targetPage = pages.getOrNull(conflict.remotePageIndex) ?: return
        currentChapter.requestedPage = targetPage.index
        currentChapter.chapter.last_page_read = targetPage.index
        chapterPageIndex = targetPage.index
        currentChapter.pageLoader?.setActivePage(targetPage)
        state.value.viewer?.moveToPage(targetPage)
        if (!incognitoMode) {
            viewModelScope.launchNonCancellable {
                updateChapter.await(
                    ChapterUpdate(
                        id = conflict.chapterId,
                        read = conflict.remotePageIndex == pages.lastIndex,
                        lastPageRead = conflict.remotePageIndex.toLong(),
                    ),
                )
                if (conflict.migratesLegacyEpubProgress) {
                    komgaProgressSyncService.pushPageProgress(
                        sourceId = manga?.source ?: return@launchNonCancellable,
                        chapterUrl = currentChapter.chapter.url,
                        pageIndex = targetPage.index,
                        totalPages = pages.size,
                    )
                }
            }
        }
    }

    private fun downloadNextChapters() {
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
        if (sourceManager.get(manga.source) is KomgaSource) return
        val chapter = currentChapter.chapter.toDomainChapter() ?: return
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
        return downloadManager.getQueuedDownloadOrNull(currentChapter.chapter.id!!)?.also {
            downloadManager.cancelQueuedDownloads(listOf(it))
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last chapter actually exists.
     * If both conditions are satisfied enqueues chapter for delete
     * @param currentChapter current chapter, which is going to be marked as read.
     */
    private fun deleteChapterIfNeeded(currentChapter: ReaderChapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots.get()
        if (removeAfterReadSlots == -1) return

        // Determine which chapter should be deleted and enqueue
        val currentChapterPosition = chapterList.indexOf(currentChapter)
        val chapterToDelete = chapterList.getOrNull(currentChapterPosition - removeAfterReadSlots)

        // If chapter is completely read, no need to download it
        chapterToDownload = null

        if (chapterToDelete != null) {
            enqueueDeleteReadChapters(chapterToDelete)
        }
    }

    /**
     * Saves the chapter progress (last read page and whether it's read)
     * if incognito mode isn't on.
     */
    private suspend fun updateChapterProgress(readerChapter: ReaderChapter, page: Page) {
        val pageIndex = page.index

        mutableState.update {
            it.copy(currentPage = pageIndex + 1)
        }
        readerChapter.requestedPage = pageIndex
        chapterPageIndex = pageIndex

        if (!incognitoMode && page.status !is Page.State.Error) {
            readerChapter.chapter.last_page_read = pageIndex

            if (readerChapter.pages?.lastIndex == pageIndex) {
                updateChapterProgressOnComplete(readerChapter)
            }

            updateChapter.await(
                ChapterUpdate(
                    id = readerChapter.chapter.id!!,
                    read = readerChapter.chapter.read,
                    lastPageRead = readerChapter.chapter.last_page_read.toLong(),
                ),
            )
        }
    }

    private suspend fun updateChapterProgressOnComplete(readerChapter: ReaderChapter) {
        readerChapter.chapter.read = true
        updateTrackChapterRead(readerChapter)
        deleteChapterIfNeeded(readerChapter)

        komgaProgressSyncService.pushPageProgress(
            sourceId = manga?.source ?: return,
            chapterUrl = readerChapter.chapter.url,
            pageIndex = readerChapter.pages?.lastIndex ?: 0,
            totalPages = readerChapter.pages?.size ?: 0,
        )

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        if (!markDuplicateAsRead) return

        val duplicateUnreadChapters = unfilteredChapterList
            .mapNotNull { chapter ->
                if (
                    !chapter.read &&
                    chapter.isRecognizedNumber &&
                    chapter.chapterNumber.toFloat() == readerChapter.chapter.chapter_number
                ) {
                    ChapterUpdate(id = chapter.id, read = true)
                } else {
                    null
                }
            }
        updateChapter.awaitAll(duplicateUnreadChapters)
    }

    fun restartReadTimer() {
        chapterReadStartTime = Instant.now().toEpochMilli()
    }

    /**
     * Saves the chapter last read history if incognito mode isn't on.
     */
    suspend fun updateHistory() {
        getCurrentChapter()?.let { readerChapter ->
            if (incognitoMode) return@let

            val chapterId = readerChapter.chapter.id!!
            val endTime = Date()
            val sessionReadDuration = chapterReadStartTime?.let { endTime.time - it } ?: 0

            if (sessionReadDuration > 0) {
                komgaProgressSyncService.pushPageProgress(
                    sourceId = manga?.source ?: return@let,
                    chapterUrl = readerChapter.chapter.url,
                    pageIndex = readerChapter.chapter.last_page_read,
                    totalPages = readerChapter.pages?.size ?: 0,
                )
            }

            upsertHistory.await(HistoryUpdate(chapterId, endTime, sessionReadDuration))
            chapterReadStartTime = null
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
        val chapter = getCurrentChapter()?.chapter ?: return
        val bookmarked = !chapter.bookmark
        chapter.bookmark = bookmarked

        viewModelScope.launchNonCancellable {
            updateChapter.await(
                ChapterUpdate(
                    id = chapter.id!!,
                    bookmark = bookmarked,
                ),
            )
        }

        mutableState.update {
            it.copy(
                bookmarked = bookmarked,
            )
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
    ): String {
        val chapter = page.chapter.chapter
        val filenameSuffix = " - ${page.number}"
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

    fun openPageDialog(page: ReaderPage) {
        mutableState.update { it.copy(dialog = Dialog.PageActions(page)) }
    }

    fun openSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.Settings) }
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
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val filename = generateFilename(manga, page)

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
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Pictures.create(relativePath),
                    ),
                )
                withUIContext {
                    notifier.onComplete(uri)
                    eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
                }
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
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
        val destDir = context.cacheImageDir

        val filename = generateFilename(manga, page)

        try {
            viewModelScope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, page))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
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
                manga.editCover(stream())
                if (manga.favorite) {
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
    private fun updateTrackChapterRead(readerChapter: ReaderChapter) {
        if (incognitoMode) return
        if (!trackPreferences.autoUpdateTrack.get()) return

        val manga = manga ?: return
        val context = Injekt.get<Application>()

        viewModelScope.launchNonCancellable {
            trackChapter.await(context, manga.id, readerChapter.chapter.chapter_number.toDouble())
        }
    }

    /**
     * Enqueues this [chapter] to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteReadChapters(chapter: ReaderChapter) {
        if (!chapter.chapter.read) return
        val manga = manga ?: return

        viewModelScope.launchNonCancellable {
            downloadManager.enqueueChaptersToDelete(listOf(chapter.chapter.toDomainChapter()!!), manga)
        }
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
        val currentPage: Int = -1,

        /**
         * Viewer used to display the pages (pager, webtoon, ...).
         */
        val viewer: Viewer? = null,
        val dialog: Dialog? = null,
        val remoteProgressConflict: MangaRemoteProgressConflict? = null,
        val menuVisible: Boolean = false,
        @IntRange(from = -100, to = 100) val brightnessOverlayValue: Int = 0,
    ) {
        val currentChapter: ReaderChapter?
            get() = viewerChapters?.currChapter

        val totalPages: Int
            get() = currentChapter?.pages?.size ?: -1
    }

    sealed interface Dialog {
        data object Loading : Dialog
        data object Settings : Dialog
        data object ReadingModeSelect : Dialog
        data object OrientationModeSelect : Dialog
        data class PageActions(val page: ReaderPage) : Dialog
    }

    sealed interface Event {
        data object ReloadViewerChapters : Event
        data object PageChanged : Event
        data class SetOrientation(val orientation: Int) : Event
        data class SetCoverResult(val result: SetAsCoverResult) : Event

        data class SavedImage(val result: SaveImageResult) : Event
        data class ShareImage(val uri: Uri, val page: ReaderPage) : Event
        data class CopyImage(val uri: Uri) : Event
    }
}

package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.domain.chapter.interactor.DownloadNewChapters
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import koharia.connection.ChapterFetchOrigin
import koharia.connection.FetchedChapters
import koharia.connection.fetchChapters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import tachiyomi.core.common.DocumentationUrls
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.SourceNotInstalledException
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class)
class LibraryUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val sourceManager: SourceManager = Injekt.get()
    private val libraryPreferences: LibraryPreferences = Injekt.get()
    private val downloadManager: DownloadManager = Injekt.get()
    private val getLibraryManga: GetLibraryManga = Injekt.get()
    private val getManga: GetManga = Injekt.get()
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get()
    private val fetchInterval: FetchInterval = Injekt.get()
    private val downloadNewChapters: DownloadNewChapters = Injekt.get()

    private val notifier = LibraryUpdateNotifier(context)

    private var mangaToUpdate: List<LibraryManga> = mutableListOf()

    private lateinit var requestedSource: Source
    private lateinit var requestedOwner: koharia.connection.ConnectionChapterOwner
    private lateinit var requestedOrigin: ChapterFetchOrigin

    override suspend fun doWork(): Result {
        if (koharia.connection.DevReadOnlyValidation.enabled) return Result.retry()
        setupTask(context)
        val sourceId = inputData.keyValueMap[KEY_SOURCE] as? Long ?: return Result.success()
        val ownerKey = inputData.getString(KEY_OWNER) ?: return Result.success()
        requestedOrigin = inputData.getString(KEY_ORIGIN)?.let {
            runCatching { ChapterFetchOrigin.valueOf(it) }.getOrNull()
        }?.takeIf { it == ChapterFetchOrigin.EXPLICIT_REFRESH || it == ChapterFetchOrigin.SERVER_UPDATE }
            ?: return Result.success()
        if (koharia.connection.ConnectionRestoreState.isRestoring ||
            Injekt.get<koharia.connection.SharedConfigMigration>().isPending()
        ) {
            return Result.retry()
        }
        val source = sourceManager.get(sourceId) ?: return Result.success()
        val owner = (source as? koharia.connection.ConnectionChapterSessionAdapter)?.captureChapterOwner()
            ?: return Result.failure()
        if (owner.key != ownerKey) return Result.success()
        requestedSource = source
        requestedOwner = owner
        owner.checkCurrent()
        // The retained notifier uses shared IDs, so only one connection owns it at a time.
        return execution.withLock {
            withIOContext {
                try {
                    checkRequestedSource()
                    setForegroundSafely()
                    libraryPreferences.lastUpdatedTimestamp.set(Instant.now().toEpochMilli())
                    addMangaToQueue()
                    updateChapterList()
                    Result.success()
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        throw e
                    } else {
                        logcat(LogPriority.ERROR, e)
                        Result.failure()
                    }
                } finally {
                    notifier.cancelProgressNotification()
                }
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notifier = LibraryUpdateNotifier(context)
        return ForegroundInfo(
            Notifications.ID_LIBRARY_PROGRESS,
            notifier.progressNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    /**
     * Adds list of manga to be updated.
     */
    private suspend fun addMangaToQueue() {
        requestedOwner.checkCurrent()
        mangaToUpdate = getLibraryManga.await()
            .filter { it.manga.source == requestedSource.id }
            .sortedBy { it.manga.title }
        requestedOwner.checkCurrent()
    }

    /**
     * Method that updates manga in [mangaToUpdate]. It's called in a background thread, so it's safe
     * to do heavy operations or network calls here.
     * For each manga it calls [updateManga] and updates the notification showing the current
     * progress.
     *
     * @return an observable delivering the progress of each update.
     */
    private suspend fun updateChapterList() {
        val semaphore = Semaphore(5)
        val progressCount = AtomicInt(0)
        val currentlyUpdatingManga = CopyOnWriteArrayList<Manga>()
        val newUpdates = CopyOnWriteArrayList<Pair<Manga, Array<Chapter>>>()
        val failedUpdates = CopyOnWriteArrayList<Pair<Manga, String?>>()
        val hasDownloads = AtomicBoolean(false)
        val fetchWindow = fetchInterval.getWindow(ZonedDateTime.now())

        coroutineScope {
            mangaToUpdate.groupBy { it.manga.source }.values
                .map { mangaInSource ->
                    async {
                        semaphore.withPermit {
                            mangaInSource.forEach { libraryManga ->
                                val manga = libraryManga.manga
                                ensureActive()

                                // Don't continue to update if manga is not in library
                                if (getManga.await(manga.id)?.favorite != true) {
                                    return@forEach
                                }

                                withUpdateNotification(
                                    currentlyUpdatingManga,
                                    progressCount,
                                    manga,
                                ) {
                                    try {
                                        val source = requestedSource
                                        checkRequestedSource()
                                        val (fetched, added) = updateManga(manga, source, fetchWindow)
                                        val newChapters = added.sortedByDescending { it.sourceOrder }

                                        if (newChapters.isNotEmpty()) {
                                            if (downloadNewChapters.await(
                                                    manga,
                                                    fetched,
                                                    newChapters,
                                                    autoStart = false,
                                                )
                                            ) {
                                                hasDownloads.store(true)
                                            }

                                            // Convert to the manga that contains new chapters
                                            newUpdates.add(manga to newChapters.toTypedArray())
                                        }
                                    } catch (e: Throwable) {
                                        if (e is CancellationException) throw e
                                        val errorMessage = when (e) {
                                            is NoChaptersException -> context.stringResource(
                                                MR.strings.no_chapters_error,
                                            )
                                            // failedUpdates will already have the source, don't need to copy it into the message
                                            is SourceNotInstalledException -> context.stringResource(
                                                MR.strings.loader_not_implemented_error,
                                            )
                                            else -> e.message
                                        }
                                        failedUpdates.add(manga to errorMessage)
                                    }
                                }
                            }
                        }
                    }
                }
                .awaitAll()
        }

        notifier.cancelProgressNotification()

        if (newUpdates.isNotEmpty()) {
            checkRequestedSource()
            if (hasDownloads.load()) {
                downloadManager.startQueuedDownloadsOnly()
            }
            notifier.showUpdateNotifications(newUpdates)
        }

        if (failedUpdates.isNotEmpty()) {
            val errorFile = writeErrorFile(failedUpdates)
            notifier.showUpdateErrorNotification(
                failedUpdates.size,
                errorFile.getUriCompat(context),
            )
        }
    }

    /**
     * Updates the chapters for the given manga and adds them to the database.
     *
     * @param manga the manga to update.
     * @return a pair of the inserted and removed chapters.
     */
    private suspend fun updateManga(
        manga: Manga,
        source: Source,
        fetchWindow: Pair<Long, Long>,
    ): Pair<FetchedChapters, List<Chapter>> {
        checkRequestedSource()
        val chapters = source.fetchChapters(manga.toSManga(), requestedOrigin, forceNetwork = true)
        checkRequestedSource()
        // Get manga from database to account for if it was removed during the update and
        // to get latest data so it doesn't get overwritten later on
        val dbManga = getManga.await(manga.id)?.takeIf { it.favorite } ?: return chapters to emptyList()

        return chapters to syncChaptersWithSource.await(chapters, dbManga, false, fetchWindow)
    }

    private fun checkRequestedSource() {
        if (sourceManager.get(requestedSource.id) !== requestedSource) {
            throw CancellationException("Chapter refresh source replaced")
        }
        requestedOwner.checkCurrent()
    }
    private suspend fun withUpdateNotification(
        updatingManga: CopyOnWriteArrayList<Manga>,
        completed: AtomicInt,
        manga: Manga,
        block: suspend () -> Unit,
    ) = coroutineScope {
        ensureActive()

        updatingManga.add(manga)
        notifier.showProgressNotification(
            updatingManga,
            completed.load(),
            mangaToUpdate.size,
        )

        block()

        ensureActive()

        updatingManga.remove(manga)
        completed.incrementAndFetch()
        notifier.showProgressNotification(
            updatingManga,
            completed.load(),
            mangaToUpdate.size,
        )
    }

    /**
     * Writes basic file of update errors to cache dir.
     */
    private fun writeErrorFile(errors: List<Pair<Manga, String?>>): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("mihon_update_errors.txt")
                file.bufferedWriter().use { out ->
                    out.write(
                        context.stringResource(
                            MR.strings.library_errors_help,
                            DocumentationUrls.troubleshooting(context),
                        ) + "\n\n",
                    )
                    // Error file format:
                    // ! Error
                    //   # Source
                    //     - Manga
                    errors.groupBy({ it.second }, { it.first }).forEach { (error, mangas) ->
                        out.write("\n! ${error}\n")
                        mangas.groupBy { it.source }.forEach { (srcId, mangas) ->
                            val source = sourceManager.getOrStub(srcId)
                            out.write("  # $source\n")
                            mangas.forEach {
                                out.write("    - ${it.title}\n")
                            }
                        }
                    }
                }
                return file
            }
        } catch (_: Exception) {}
        return File("")
    }

    companion object {
        private const val TAG = "ConnectionChapterRefresh"
        private const val WORK_NAME_AUTO = "LibraryUpdate-auto"
        private const val WORK_NAME_MANUAL = "LibraryUpdate-manual"
        private const val KEY_SOURCE = "connection"
        private const val KEY_OWNER = "owner"
        private const val KEY_ORIGIN = "origin"
        private val scheduling = kotlinx.coroutines.sync.Mutex()
        private val execution = kotlinx.coroutines.sync.Mutex()

        internal fun workName(sourceId: Long, ownerKey: String) = "$TAG-$sourceId-$ownerKey"

        // Kept for old migrations, restores and serialized WorkManager records.
        @Suppress("UNUSED_PARAMETER")
        fun setupTask(context: Context, prefInterval: Int? = null) {
            if (koharia.connection.DevReadOnlyValidation.enabled) return
            context.workManager.cancelUniqueWork(WORK_NAME_AUTO)
            context.workManager.cancelUniqueWork(WORK_NAME_MANUAL)
        }

        @Suppress("UNUSED_PARAMETER")
        fun startNow(context: Context, category: Category? = null): Boolean {
            setupTask(context)
            return false
        }

        fun stop(context: Context) {
            setupTask(context)
            context.workManager.cancelAllWorkByTag(TAG)
        }

        suspend fun request(
            source: Source,
            origin: ChapterFetchOrigin,
            expectedOwner: String? = null,
            context: Context = Injekt.get<android.app.Application>(),
        ): Boolean {
            if (koharia.connection.DevReadOnlyValidation.enabled) return false
            require(origin == ChapterFetchOrigin.EXPLICIT_REFRESH || origin == ChapterFetchOrigin.SERVER_UPDATE)
            val manager = Injekt.get<SourceManager>()
            if (manager.get(source.id) !== source || koharia.connection.ConnectionRestoreState.isRestoring) return false
            val owner = (source as koharia.connection.ConnectionChapterSessionAdapter).captureChapterOwner()
            if (expectedOwner != null && owner.key != expectedOwner) return false
            return scheduling.withLock {
                owner.checkCurrent()
                if (manager.get(source.id) !== source) return@withLock false
                val name = workName(source.id, owner.key)
                val wm = context.workManager
                val states = wm.getWorkInfosForUniqueWork(name).await()
                if (states.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) {
                    return@withLock false
                }
                val request = OneTimeWorkRequestBuilder<LibraryUpdateJob>()
                    .setInputData(
                        workDataOf(KEY_SOURCE to source.id, KEY_OWNER to owner.key, KEY_ORIGIN to origin.name),
                    )
                    .addTag(TAG)
                    .addTag(name)
                    .setInitialDelay(1, TimeUnit.SECONDS)
                    .build()
                owner.checkCurrent()
                wm.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.await()
                true
            }
        }
    }
}

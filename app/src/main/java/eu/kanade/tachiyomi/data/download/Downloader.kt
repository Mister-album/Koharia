package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.getComicInfo
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.DiskUtil.NOMEDIA_FILE
import eu.kanade.tachiyomi.util.storage.saveTo
import koharia.core.archive.ZipWriter
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNow
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * This class is the one in charge of downloading chapters.
 *
 * Its queue contains the list of chapters to download.
 */
class Downloader(
    private val context: Context,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val sourceManager: SourceManager = Injekt.get(),
    private val chapterCache: ChapterCache = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val xml: XML = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
) {

    /**
     * Store for persisting downloads across restarts.
     */
    private val store = DownloadStore(context)

    /**
     * Queue where active downloads are kept.
     */
    private val _queueState = MutableStateFlow<List<Download>>(emptyList())
    val queueState = _queueState.asStateFlow()
    private val restoreQueueLock = Any()

    @Volatile
    private var isQueueRestored = false

    /**
     * Notifier for the downloader state and progress.
     */
    private val notifier by lazy { DownloadNotifier(context) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloaderJob: Job? = null

    /**
     * Whether the downloader is running.
     */
    val isRunning: Boolean
        get() = downloaderJob?.isActive ?: false

    /**
     * Whether the downloader is paused
     */
    @Volatile
    var isPaused: Boolean = false

    init {
        launchNow {
            restorePersistedQueueIfNeeded()
        }
    }

    /**
     * Starts the downloader. It doesn't do anything if it's already running or there isn't anything
     * to download.
     *
     * @return true if the downloader is started, false otherwise.
     */
    fun start(): Boolean {
        val queueSizeBeforeRestore = queueState.value.size
        restorePersistedQueueIfNeeded()
        val queue = queueState.value
        val pending = queue.filter { it.status != Download.State.DOWNLOADED }

        if (isRunning || queue.isEmpty()) {
            logcat(LogPriority.INFO) {
                "Downloader.start(): early return, " +
                    "isRunning=$isRunning, " +
                    "queueSizeBeforeRestore=$queueSizeBeforeRestore, " +
                    "queueSizeAfterRestore=${queue.size}, " +
                    "pendingCount=${pending.size}, " +
                    "statusSummary=${queue.statusSummary()}"
            }
            return false
        }

        pending.forEach { if (it.status != Download.State.QUEUE) it.status = Download.State.QUEUE }

        isPaused = false

        launchDownloaderJob()

        logcat(LogPriority.INFO) {
            "Downloader.start(): started=${
                pending.isNotEmpty()
            }, queueSize=${queue.size}, pendingCount=${pending.size}, statusSummary=${queue.statusSummary()}"
        }

        return pending.isNotEmpty()
    }

    private fun restorePersistedQueueIfNeeded() {
        if (isQueueRestored) return

        synchronized(restoreQueueLock) {
            if (isQueueRestored) return

            val existingChapterIds = queueState.value.mapTo(mutableSetOf()) { it.chapter.id }
            val restoredDownloads = store.restore()
                .filterNot { it.chapter.id in existingChapterIds }

            if (restoredDownloads.isNotEmpty()) {
                logcat(LogPriority.INFO) {
                    "Downloader.restorePersistedQueueIfNeeded(): restoring ${restoredDownloads.size} downloads"
                }
                addAllToQueue(restoredDownloads)
            } else {
                logcat(LogPriority.INFO) {
                    "Downloader.restorePersistedQueueIfNeeded(): no persisted downloads to restore"
                }
            }

            isQueueRestored = true
        }
    }

    /**
     * Stops the downloader.
     */
    fun stop(reason: String? = null) {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.ERROR }

        if (reason != null) {
            notifier.onWarning(reason)
            return
        }

        if (isPaused && queueState.value.isNotEmpty()) {
            notifier.onPaused()
        } else {
            notifier.onComplete()
        }

        isPaused = false

        DownloadJob.stop(context)
    }

    /**
     * Pauses the downloader
     */
    fun pause() {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.QUEUE }
        isPaused = true
    }

    /**
     * Removes everything from the queue.
     */
    fun clearQueue() {
        cancelDownloaderJob()

        internalClearQueue()
        notifier.dismissProgress()
    }

    /**
     * Prepares the subscriptions to start downloading.
     */
    private fun launchDownloaderJob() {
        if (isRunning) return

        downloaderJob = scope.launch {
            val activeDownloadsFlow = combine(
                queueState,
                downloadPreferences.parallelSourceLimit.changes(),
            ) { a, b -> a to b }.transformLatest { (queue, parallelCount) ->
                while (true) {
                    val activeDownloads = queue.asSequence()
                        // Ignore completed downloads, leave them in the queue
                        .filter { it.status.value <= Download.State.DOWNLOADING.value }
                        .groupBy { it.source }
                        .toList()
                        .take(parallelCount)
                        .map { (_, downloads) -> downloads.first() }
                    emit(activeDownloads)

                    if (activeDownloads.isEmpty()) break
                    // Suspend until a download enters the ERROR state
                    val activeDownloadsErroredFlow =
                        combine(activeDownloads.map(Download::statusFlow)) { states ->
                            states.contains(Download.State.ERROR)
                        }.filter { it }
                    activeDownloadsErroredFlow.first()
                }
            }
                .distinctUntilChanged()

            // Use supervisorScope to cancel child jobs when the downloader job is cancelled
            supervisorScope {
                val downloadJobs = mutableMapOf<Download, Job>()

                activeDownloadsFlow.collectLatest { activeDownloads ->
                    val downloadJobsToStop = downloadJobs.filter { it.key !in activeDownloads }
                    downloadJobsToStop.forEach { (download, job) ->
                        job.cancel()
                        downloadJobs.remove(download)
                    }

                    val downloadsToStart = activeDownloads.filter { it !in downloadJobs }
                    downloadsToStart.forEach { download ->
                        downloadJobs[download] = launchDownloadJob(download)
                    }
                }
            }
        }
    }

    private fun CoroutineScope.launchDownloadJob(download: Download) = launchIO {
        try {
            downloadChapter(download)

            // Remove successful download from queue
            if (download.status == Download.State.DOWNLOADED) {
                removeFromQueue(download)
            }
            if (areAllDownloadsFinished()) {
                stop()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            notifier.onError(e.message)
            stop()
        }
    }

    /**
     * Destroys the downloader subscriptions.
     */
    private fun cancelDownloaderJob() {
        downloaderJob?.cancel()
        downloaderJob = null
    }

    /**
     * Creates a download object for every chapter and adds them to the downloads queue.
     *
     * @param manga the manga of the chapters to download.
     * @param chapters the list of chapters to download.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     */
    fun queueChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean, mode: Download.Mode?) {
        if (chapters.isEmpty()) return

        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        val resolvedMode = resolveDownloadMode(source, mode)
        val wasEmpty = queueState.value.isEmpty()
        val chaptersToQueue = chapters.asSequence()
            // Filter out those already downloaded.
            .filter { provider.findChapterDir(it.name, it.scanlator, it.url, manga.title, source) == null }
            // Add chapters to queue from the start.
            .sortedByDescending { it.sourceOrder }
            // Filter out those already enqueued.
            .filter { chapter -> queueState.value.none { it.chapter.id == chapter.id } }
            // Create a download for each one.
            .map { Download(source, manga, it, resolvedMode) }
            .toList()

        if (chaptersToQueue.isNotEmpty()) {
            addAllToQueue(chaptersToQueue)

            // Start downloader if needed
            if (autoStart && wasEmpty) {
                val queuedDownloads = queueState.value.count { it.source !is UnmeteredSource }
                val maxDownloadsFromSource = queueState.value
                    .groupBy { it.source }
                    .filterKeys { it !is UnmeteredSource }
                    .maxOfOrNull { it.value.size }
                    ?: 0
                if (
                    queuedDownloads > DOWNLOADS_QUEUED_WARNING_THRESHOLD ||
                    maxDownloadsFromSource > CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD
                ) {
                    notifier.onWarning(
                        context.stringResource(
                            MR.strings.download_queue_size_warning,
                            context.stringResource(MR.strings.app_name),
                        ),
                        WARNING_NOTIF_TIMEOUT_MS,
                        NotificationHandler.openUrl(context, LibraryUpdateNotifier.HELP_WARNING_URL),
                    )
                }
                DownloadJob.start(context)
            }
        }
    }

    /**
     * Downloads a chapter.
     *
     * @param download the chapter to be downloaded.
     */
    private suspend fun downloadChapter(download: Download) {
        val mangaDir = provider.getMangaDir(download.manga.title, download.source).getOrElse { e ->
            download.status = Download.State.ERROR
            notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
            return
        }

        val availSpace = DiskUtil.getAvailableStorageSpace(mangaDir)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            download.status = Download.State.ERROR
            notifier.onError(
                context.stringResource(MR.strings.download_insufficient_space),
                download.chapter.name,
                download.manga.title,
                download.manga.id,
            )
            return
        }

        val chapterDirname = provider.getChapterDirName(
            download.chapter.name,
            download.chapter.scanlator,
            download.chapter.url,
        )

        if (download.mode == Download.Mode.RAW_FILE && tryDownloadRawFile(download, mangaDir, chapterDirname)) {
            return
        }

        val tmpDir = mangaDir.createDirectory(chapterDirname + TMP_DIR_SUFFIX)!!

        try {
            // If the page list already exists, start from the file
            val pageList = download.pages ?: run {
                // Otherwise, pull page list from network and add them to download object
                val pages = download.source.getPageList(download.chapter.toSChapter())

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }
                // Don't trust index from source
                val reIndexedPages = pages.mapIndexed { index, page -> Page(index, page.url, page.imageUrl, page.uri) }
                download.pages = reIndexedPages
                reIndexedPages
            }

            // Delete all temporary (unfinished) files
            tmpDir.listFiles()
                ?.filter { it.extension == "tmp" }
                ?.forEach { it.delete() }

            download.status = Download.State.DOWNLOADING

            // Start downloading images, consider we can have downloaded images already
            pageList.asFlow().flatMapMerge(concurrency = downloadPreferences.parallelPageLimit.get()) { page ->
                flow {
                    // Fetch image URL if necessary
                    if (page.imageUrl.isNullOrEmpty()) {
                        page.status = Page.State.LoadPage
                        try {
                            page.imageUrl = download.source.getImageUrl(page)
                        } catch (e: Throwable) {
                            page.status = Page.State.Error(e)
                        }
                    }

                    withIOContext { getOrDownloadImage(page, download, tmpDir) }
                    emit(page)
                }
                    .flowOn(Dispatchers.IO)
            }
                .collect {
                    // Do when page is downloaded.
                    notifier.onProgressChange(download)
                }

            // Do after download completes

            if (!isDownloadSuccessful(download, tmpDir)) {
                download.status = Download.State.ERROR
                return
            }

            createComicInfoFile(
                tmpDir,
                download.manga,
                download.chapter,
                download.source,
            )

            // Only rename the directory if it's downloaded
            if (downloadPreferences.saveChaptersAsCBZ.get()) {
                archiveChapter(mangaDir, chapterDirname, tmpDir)
            } else {
                tmpDir.renameTo(chapterDirname)
            }
            cache.addChapter(chapterDirname, mangaDir, download.manga)

            DiskUtil.createNoMediaFile(tmpDir, context)

            download.status = Download.State.DOWNLOADED
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // If the page list threw, it will resume here
            logcat(LogPriority.ERROR, error)
            download.status = Download.State.ERROR
            notifier.onError(error.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    private fun resolveDownloadMode(source: HttpSource, mode: Download.Mode?): Download.Mode {
        return mode ?: if (source is KomgaSource) Download.Mode.RAW_FILE else Download.Mode.PAGE_CACHE
    }

    private suspend fun tryDownloadRawFile(download: Download, mangaDir: UniFile, chapterDirname: String): Boolean {
        val source = download.source as? KomgaSource ?: return false

        fun tmpFileFor(extension: String): Pair<String, UniFile> {
            val finalFileName = "$chapterDirname.$extension"
            val tmpFileName = "$finalFileName$TMP_DIR_SUFFIX"
            val existing = mangaDir.findFile(tmpFileName)
            val tmpFile = existing ?: mangaDir.createFile(tmpFileName)
                ?: error("Failed to create raw download file: $tmpFileName")
            return finalFileName to tmpFile
        }

        repeat(4) { attempt ->
            val existingRawTmpFile = findExistingRawTmpFile(mangaDir, chapterDirname)
            var finalFileName = existingRawTmpFile?.first
            var tmpFile = existingRawTmpFile?.second
            val existingBytes = tmpFile?.length()?.takeIf { it > 0L } ?: 0L

            val coroutineContext = currentCoroutineContext()
            val responseCall = source.client.newCall(
                source.rawFileRequest(
                    download.chapter.url,
                    existingBytes.takeIf {
                        it >
                            0L
                    },
                ),
            )
            val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause: Throwable? ->
                if (cause != null) {
                    responseCall.cancel()
                }
            }
            try {
                val response = responseCall.execute()
                response.use {
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code}")
                    }

                    val extension = finalFileName
                        ?.substringAfterLast('.', "")
                        ?.takeIf { it.isNotBlank() }
                        ?: resolveRawFileExtension(response)
                    if (!DownloadProvider.isSupportedChapterFileExtension(extension)) {
                        logcat(LogPriority.INFO) {
                            "Downloader.tryDownloadRawFile(): unsupported raw file extension=$extension, falling back to page cache"
                        }
                        return false
                    }

                    if (tmpFile == null || finalFileName == null) {
                        val created = tmpFileFor(extension!!)
                        finalFileName = created.first
                        tmpFile = created.second
                    }

                    val resolvedFinalFileName = checkNotNull(finalFileName)
                    var resolvedTmpFile = checkNotNull(tmpFile)

                    if (existingBytes > 0L && response.code == 200) {
                        resolvedTmpFile.delete()
                        resolvedTmpFile = mangaDir.createFile("$resolvedFinalFileName$TMP_DIR_SUFFIX")
                            ?: error("Failed to recreate raw download file: $resolvedFinalFileName$TMP_DIR_SUFFIX")
                    }

                    val resumedBytes = if (response.code == 206) existingBytes else 0L
                    val totalBytes = resolveRawFileTotalBytes(response, resumedBytes)
                    download.updateRawProgress(resumedBytes, totalBytes)
                    download.status = Download.State.DOWNLOADING

                    val outputMode = if (resumedBytes > 0L) "wa" else "w"
                    context.contentResolver.openOutputStream(resolvedTmpFile.uri, outputMode)?.use { output ->
                        response.body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var downloadedBytes = resumedBytes
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                download.updateRawProgress(downloadedBytes, totalBytes)
                                notifier.onProgressChange(download)
                            }
                            output.flush()
                            download.updateRawProgress(downloadedBytes, totalBytes.coerceAtLeast(downloadedBytes))
                        }
                    } ?: error("Failed to open raw download output stream: ${resolvedTmpFile.name}")

                    mangaDir.findFile(resolvedFinalFileName)?.delete()
                    if (!resolvedTmpFile.renameTo(resolvedFinalFileName)) {
                        error("Failed to finalize raw download file: $resolvedFinalFileName")
                    }

                    cache.addChapter(resolvedFinalFileName, mangaDir, download.manga)
                    DiskUtil.createNoMediaFile(mangaDir, context)
                    download.status = Download.State.DOWNLOADED

                    logcat(LogPriority.INFO) {
                        "Downloader.tryDownloadRawFile(): saved raw file $resolvedFinalFileName"
                    }
                }

                return true
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (error is IOException && attempt < 3) {
                    logcat(LogPriority.WARN, error) {
                        "Downloader.tryDownloadRawFile(): retrying after failure, attempt=${attempt + 1}, existingBytes=$existingBytes"
                    }
                    delay((2L shl attempt) * 1000)
                } else {
                    throw error
                }
            } finally {
                cancellationHandle?.dispose()
            }
        }

        return false
    }

    private fun findExistingRawTmpFile(mangaDir: UniFile, chapterDirname: String): Pair<String, UniFile>? {
        return mangaDir.listFiles()
            ?.firstOrNull { file ->
                !file.isDirectory &&
                    file.name.orEmpty().startsWith("$chapterDirname.") &&
                    file.name.orEmpty().endsWith(TMP_DIR_SUFFIX)
            }
            ?.let { tmpFile ->
                tmpFile.name
                    ?.removeSuffix(TMP_DIR_SUFFIX)
                    ?.takeIf { it != tmpFile.name }
                    ?.let { finalFileName -> finalFileName to tmpFile }
            }
    }

    private fun resolveRawFileExtension(response: Response): String? {
        val header = response.header("Content-Disposition")
        val filename = header?.let(::parseContentDispositionFilename)
        return filename
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() }
            ?.lowercase(Locale.ROOT)
    }

    private fun resolveRawFileTotalBytes(response: Response, resumedBytes: Long): Long {
        val contentRangeTotal = response.header("Content-Range")
            ?.substringAfterLast('/')
            ?.toLongOrNull()
        if (contentRangeTotal != null && contentRangeTotal > 0L) {
            return contentRangeTotal
        }

        val bodyLength = response.body.contentLength().takeIf { it > 0L } ?: 0L
        return if (response.code == 206) {
            resumedBytes + bodyLength
        } else {
            bodyLength
        }
    }

    private fun parseContentDispositionFilename(header: String): String? {
        val encodedMatch = Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE).find(header)
        if (encodedMatch != null) {
            return URLDecoder.decode(encodedMatch.groupValues[1].trim('"'), StandardCharsets.UTF_8.name())
        }

        val plainMatch = Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE).find(header)
        return plainMatch?.groupValues?.getOrNull(1)
    }

    /**
     * Gets the image from the filesystem if it exists or downloads it otherwise.
     *
     * @param page the page to download.
     * @param download the download of the page.
     * @param tmpDir the temporary directory of the download.
     */
    private suspend fun getOrDownloadImage(page: Page, download: Download, tmpDir: UniFile) {
        // If the image URL is empty, do nothing
        if (page.imageUrl == null) {
            return
        }

        val digitCount = (download.pages?.size ?: 0).toString().length.coerceAtLeast(3)
        val filename = "%0${digitCount}d".format(Locale.ENGLISH, page.number)
        val tmpFile = tmpDir.findFile("$filename.tmp")

        // Delete temp file if it exists
        tmpFile?.delete()

        // Try to find the image file
        val imageFile = tmpDir.listFiles()?.firstOrNull {
            it.name!!.startsWith("$filename.") || it.name!!.startsWith("${filename}__001")
        }

        try {
            // If the image is already downloaded, do nothing. Otherwise download from network
            val file = when {
                imageFile != null -> imageFile
                chapterCache.isImageInCache(
                    page.imageUrl!!,
                ) -> copyImageFromCache(chapterCache.getImageFile(page.imageUrl!!), tmpDir, filename)
                else -> downloadImage(page, download.source, tmpDir, filename)
            }

            // When the page is ready, set page path, progress (just in case) and status
            splitTallImageIfNeeded(page, tmpDir)

            page.uri = file.uri
            page.progress = 100
            page.status = Page.State.Ready
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // Mark this page as error and allow to download the remaining
            page.progress = 0
            page.status = Page.State.Error(e)
            notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
        }
    }

    /**
     * Downloads the image from network to a file in tmpDir.
     *
     * @param page the page to download.
     * @param source the source of the page.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private suspend fun downloadImage(page: Page, source: HttpSource, tmpDir: UniFile, filename: String): UniFile {
        page.status = Page.State.DownloadImage
        page.progress = 0
        return flow {
            val response = source.getImage(page)
            val file = tmpDir.createFile("$filename.tmp")!!
            try {
                response.body.source().saveTo(file.openOutputStream())
                val extension = getImageExtension(response, file)
                file.renameTo("$filename.$extension")
            } catch (e: Exception) {
                response.close()
                file.delete()
                throw e
            }
            emit(file)
        }
            // Retry 3 times, waiting 2, 4 and 8 seconds between attempts.
            .retryWhen { _, attempt ->
                if (attempt < 3) {
                    delay((2L shl attempt.toInt()) * 1000)
                    true
                } else {
                    false
                }
            }
            .first()
    }

    /**
     * Copies the image from cache to file in tmpDir.
     *
     * @param cacheFile the file from cache.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private fun copyImageFromCache(cacheFile: File, tmpDir: UniFile, filename: String): UniFile {
        val tmpFile = tmpDir.createFile("$filename.tmp")!!
        cacheFile.inputStream().use { input ->
            tmpFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        val extension = ImageUtil.findImageType(cacheFile.inputStream()) ?: return tmpFile
        tmpFile.renameTo("$filename.${extension.extension}")
        cacheFile.delete()
        return tmpFile
    }

    /**
     * Returns the extension of the downloaded image from the network response, or if it's null,
     * analyze the file. If everything fails, assume it's a jpg.
     *
     * @param response the network response of the image.
     * @param file the file where the image is already downloaded.
     */
    private fun getImageExtension(response: Response, file: UniFile): String {
        val mime = response.body.contentType()?.run { if (type == "image") "image/$subtype" else null }
        return ImageUtil.getExtensionFromMimeType(mime) { file.openInputStream() }
    }

    private fun splitTallImageIfNeeded(page: Page, tmpDir: UniFile) {
        if (!downloadPreferences.splitTallImages.get()) return

        try {
            val filenamePrefix = "%03d".format(Locale.ENGLISH, page.number)
            val imageFile = tmpDir.listFiles()?.firstOrNull { it.name.orEmpty().startsWith(filenamePrefix) }
                ?: error(context.stringResource(MR.strings.download_notifier_split_page_not_found, page.number))

            // If the original page was previously split, then skip
            if (imageFile.name.orEmpty().startsWith("${filenamePrefix}__")) return

            ImageUtil.splitTallImage(tmpDir, imageFile, filenamePrefix)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to split downloaded image" }
        }
    }

    /**
     * Checks if the download was successful.
     *
     * @param download the download to check.
     * @param tmpDir the directory where the download is currently stored.
     */
    private fun isDownloadSuccessful(
        download: Download,
        tmpDir: UniFile,
    ): Boolean {
        // Page list hasn't been initialized
        val downloadPageCount = download.pages?.size ?: return false

        // Ensure that all pages have been downloaded
        if (download.downloadedImages != downloadPageCount) {
            return false
        }

        // Ensure that the chapter folder has all the pages
        val downloadedImagesCount = tmpDir.listFiles().orEmpty().count {
            val fileName = it.name.orEmpty()
            when {
                fileName in listOf(COMIC_INFO_FILE, NOMEDIA_FILE) -> false
                fileName.endsWith(".tmp") -> false
                // Only count the first split page and not the others
                fileName.contains("__") && !fileName.endsWith("__001.jpg") -> false
                else -> true
            }
        }
        return downloadedImagesCount == downloadPageCount
    }

    /**
     * Archive the chapter pages as a CBZ.
     */
    private fun archiveChapter(
        mangaDir: UniFile,
        dirname: String,
        tmpDir: UniFile,
    ) {
        val zip = mangaDir.createFile("$dirname.cbz$TMP_DIR_SUFFIX")!!
        ZipWriter(context, zip).use { writer ->
            tmpDir.listFiles()?.forEach { file ->
                writer.write(file)
            }
        }
        zip.renameTo("$dirname.cbz")
        tmpDir.delete()
    }

    /**
     * Creates a ComicInfo.xml file inside the given directory.
     */
    private suspend fun createComicInfoFile(
        dir: UniFile,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ) {
        val categories = getCategories.await(manga.id).map { it.name.trim() }.takeUnless { it.isEmpty() }
        val urls = getTracks.await(manga.id)
            .mapNotNull { track ->
                track.remoteUrl.takeUnless { url -> url.isBlank() }?.trim()
            }
            .plus(source.getChapterUrl(chapter.toSChapter()).trim())
            .distinct()

        val comicInfo = getComicInfo(
            manga,
            chapter,
            urls,
            categories,
            source.name,
        )

        // Remove the old file
        dir.findFile(COMIC_INFO_FILE)?.delete()
        dir.createFile(COMIC_INFO_FILE)!!.openOutputStream().use {
            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
            it.write(comicInfoString.toByteArray())
        }
    }

    /**
     * Returns true if all the queued downloads are in DOWNLOADED or ERROR state.
     */
    private fun areAllDownloadsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Download.State.DOWNLOADING.value }
    }

    private fun addAllToQueue(downloads: List<Download>) {
        _queueState.update {
            downloads.forEach { download ->
                download.status = Download.State.QUEUE
            }
            store.addAll(downloads)
            it + downloads
        }
    }

    private fun removeFromQueue(download: Download) {
        _queueState.update {
            store.remove(download)
            if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                download.status = Download.State.NOT_DOWNLOADED
            }
            it - download
        }
    }

    private inline fun removeFromQueueIf(predicate: (Download) -> Boolean) {
        _queueState.update { queue ->
            val downloads = queue.filter { predicate(it) }
            store.removeAll(downloads)
            downloads.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            queue - downloads
        }
    }

    fun removeFromQueue(chapters: List<Chapter>) {
        val chapterIds = chapters.map { it.id }
        removeFromQueueIf { it.chapter.id in chapterIds }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            store.clear()
            emptyList()
        }
    }

    fun updateQueue(downloads: List<Download>) {
        val wasRunning = isRunning

        if (downloads.isEmpty()) {
            clearQueue()
            stop()
            return
        }

        pause()
        internalClearQueue()
        addAllToQueue(downloads)

        if (wasRunning) {
            start()
        }
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"
        const val WARNING_NOTIF_TIMEOUT_MS = 30_000L
        const val CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 15
        private const val DOWNLOADS_QUEUED_WARNING_THRESHOLD = 30
    }
}

private fun List<Download>.statusSummary(): String {
    return if (isEmpty()) {
        "empty"
    } else {
        groupBy { it.status }
            .entries
            .sortedBy { it.key.value }
            .joinToString(separator = ",") { (status, downloads) -> "${status.name}:${downloads.size}" }
    }
}

// Arbitrary minimum required space to start a download: 200 MB
private const val MIN_DISK_SPACE = 200L * 1024 * 1024

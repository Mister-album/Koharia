package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import koharia.komga.download.KomgaChapterMemo
import koharia.source.komga.KomgaCachePolicy
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.PriorityBlockingQueue
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

private val pageListCacheWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Loader used to load chapters from an online source.
 */
internal class HttpPageLoader(
    private val chapter: ReaderChapter,
    private val source: HttpSource,
    private val chapterCache: ChapterCache = Injekt.get(),
) : PageLoader() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A priority queue with up to two network workers for Komga and one for other sources. */
    private val queue = PriorityBlockingQueue<PriorityPage>()

    private val pageLoadGate = PageLoadGate(preloadSize = 4)
    private val schedulerLock = Any()
    private val scheduledPages = Collections.newSetFromMap(IdentityHashMap<ReaderPage, Boolean>())
    private val activeLoads = mutableSetOf<ActivePageLoad>()

    private val domainChapter
        get() = checkNotNull(chapter.chapter.toDomainChapter())

    init {
        val workerCount = if (source is KomgaSource) 2 else 1
        repeat(workerCount) {
            scope.launchIO {
                while (true) {
                    val queuedPage = runInterruptible { queue.take() }
                    if (queuedPage.page.status != Page.State.Queue) {
                        synchronized(schedulerLock) {
                            scheduledPages.remove(queuedPage.page)
                        }
                        continue
                    }

                    val loadJob = launch(start = CoroutineStart.LAZY) {
                        internalLoadPage(
                            page = queuedPage.page,
                            force = queuedPage.priority == PriorityPage.RETRY,
                            isPrefetch = queuedPage.priority == PriorityPage.ADJACENT,
                        )
                    }
                    val newActiveLoad = ActivePageLoad(
                        page = queuedPage.page,
                        priority = queuedPage.priority,
                        job = loadJob,
                    )
                    val shouldStart = synchronized(schedulerLock) {
                        if (queuedPage.page.status == Page.State.Queue) {
                            activeLoads += newActiveLoad
                            true
                        } else {
                            scheduledPages.remove(queuedPage.page)
                            false
                        }
                    }
                    if (!shouldStart) {
                        loadJob.cancel()
                        continue
                    }
                    try {
                        loadJob.start()
                        loadJob.join()
                    } finally {
                        val requeuedAsActive = synchronized(schedulerLock) {
                            activeLoads.remove(newActiveLoad)
                            scheduledPages.remove(queuedPage.page)
                            // The user may select a prefetch after it was cancelled but before this cleanup.
                            if (
                                scope.isActive &&
                                queuedPage.page.status == Page.State.Queue &&
                                pageLoadGate.isActive(queuedPage.page.index)
                            ) {
                                enqueuePageLocked(queuedPage.page, PriorityPage.DEFAULT) != null
                            } else {
                                false
                            }
                        }
                        if (requeuedAsActive) {
                            logcat {
                                "MangaStartup: cancelled page requeued as active " +
                                    "chapterId=${chapter.chapter.id} page=${queuedPage.page.number}"
                            }
                        }
                    }
                }
            }
        }
    }

    override var isLocal: Boolean = false

    /**
     * Returns the page list for a chapter. It tries to return the page list from the local cache,
     * otherwise fallbacks to network.
     */
    override suspend fun getPages(): List<ReaderPage> {
        return loadPageList(forceNetwork = false).toReaderPages()
    }

    override suspend fun refreshPages(): List<ReaderPage> {
        return loadPageList(forceNetwork = true)
            .toReaderPages()
            .onEach { it.chapter = chapter }
    }

    override fun invalidatePageListCache() {
        chapterCache.removePageListFromCache(domainChapter)
    }

    private suspend fun loadPageList(forceNetwork: Boolean): List<Page> {
        val chapterSnapshot = domainChapter
        if (!forceNetwork) {
            chapterCache.getPageListFromCache(chapterSnapshot)?.let { cachedPages ->
                logcat {
                    "MangaStartup: page list source=chapter-cache " +
                        "chapterId=${chapter.chapter.id} chapterName=${chapter.chapter.name} " +
                        "chapterUrl=${chapter.chapter.url} pages=${cachedPages.size}"
                }
                return cachedPages.withPublicationVersion(chapterSnapshot.memo)
            }
        }

        logcat {
            "MangaStartup: page list source=request forceNetwork=$forceNetwork " +
                "chapterId=${chapter.chapter.id} chapterName=${chapter.chapter.name} " +
                "chapterUrl=${chapter.chapter.url}"
        }
        val sourcePages = try {
            if (forceNetwork && source is KomgaSource) {
                source.getPageList(chapter.chapter, KomgaCachePolicy.NetworkFirst)
            } else {
                source.getPageList(chapter.chapter)
            }
        } catch (error: Exception) {
            if (error is CancellationException || forceNetwork || source !is KomgaSource) throw error
            logcat {
                "MangaStartup: cached page list response failed; retrying network chapterId=${chapter.chapter.id}"
            }
            source.getPageList(chapter.chapter, KomgaCachePolicy.NetworkFirst)
        }.withPublicationVersion(chapterSnapshot.memo)
        logcat {
            "MangaStartup: page list request complete forceNetwork=$forceNetwork " +
                "chapterId=${chapter.chapter.id} pages=${sourcePages.size}"
        }
        pageListCacheWriteScope.launch {
            chapterCache.putPageListToCache(chapterSnapshot, sourcePages)
        }
        return sourcePages
    }

    private fun List<Page>.withPublicationVersion(memo: JsonObject): List<Page> {
        if (source !is KomgaSource) return this
        val pageImageCacheToken = KomgaChapterMemo.pageImageCacheToken(memo)
        return map { page ->
            val imageUrl = page.imageUrl ?: return@map page
            Page(
                index = page.index,
                url = page.url,
                imageUrl = KomgaChapterMemo.versionedPageImageUrl(imageUrl, pageImageCacheToken),
            )
        }
    }

    private fun List<Page>.toReaderPages(): List<ReaderPage> {
        return mapIndexed { index, page ->
            ReaderPage(index, page.url, page.imageUrl)
        }
    }

    override fun setActivePage(page: ReaderPage) {
        val pages = page.chapter.pages
        val activation = pageLoadGate.activate(page.index, pages?.size ?: 0)
        val needsUrgentLoad = page.status != Page.State.Ready
        if (needsUrgentLoad) {
            preemptPrefetchFor(page, reason = "active-page-missing")
        }
        synchronized(schedulerLock) {
            removeQueuedPagesLocked { queued ->
                queued.priority == PriorityPage.ADJACENT ||
                    (queued.priority == PriorityPage.DEFAULT && queued.page !== page)
            }
            enqueuePageLocked(page, PriorityPage.DEFAULT)
            if (!needsUrgentLoad && pages != null) {
                enqueuePrefetchWindowLocked(pages, activation.prefetchIndexes)
            }
        }
        if (activation.changed) {
            logcat {
                "MangaStartup: active page chapterId=${chapter.chapter.id} page=${page.number}"
            }
        }
        if (!needsUrgentLoad && activation.prefetchIndexes.isNotEmpty()) {
            logPrefetchWindow(page, activation.prefetchIndexes, reason = "page-selected")
        }
    }

    override fun onPageDisplayed(page: ReaderPage) {
        val pages = page.chapter.pages ?: return
        if (!pageLoadGate.isActive(page.index)) return
        val prefetchIndexes = pageLoadGate.onPageDisplayed(page.index, pages.size)
        synchronized(schedulerLock) {
            removeQueuedPagesLocked { it.priority == PriorityPage.ADJACENT }
            enqueuePrefetchWindowLocked(pages, prefetchIndexes)
        }
        if (prefetchIndexes.isNotEmpty()) {
            logPrefetchWindow(page, prefetchIndexes, reason = "page-displayed")
        }
    }

    private fun preemptPrefetchFor(page: ReaderPage, reason: String) {
        val prefetchLoads = synchronized(schedulerLock) {
            activeLoads.filter {
                it.priority == PriorityPage.ADJACENT && it.page !== page && it.job.isActive
            }
        }
        prefetchLoads.forEach { load ->
            load.job.cancel()
            logcat {
                "MangaStartup: active page preempted prefetch " +
                    "chapterId=${chapter.chapter.id} prefetchPage=${load.page.number} " +
                    "activePage=${page.number} reason=$reason"
            }
        }
    }

    private fun enqueuePrefetchWindowLocked(pages: List<ReaderPage>, indexes: List<Int>) {
        indexes.forEach { index ->
            pages.getOrNull(index)?.let { enqueuePageLocked(it, PriorityPage.ADJACENT) }
        }
    }

    private fun enqueuePageLocked(page: ReaderPage, priority: Int): PriorityPage? {
        if (page.status != Page.State.Queue) return null
        val queuedPage = queue.firstOrNull { it.page === page }
        if (queuedPage != null) {
            if (queuedPage.priority >= priority) return queuedPage
            queue.remove(queuedPage)
            return PriorityPage(page, priority).also { queue.offer(it) }
        }
        if (!scheduledPages.add(page)) return null
        return PriorityPage(page, priority).also { queue.offer(it) }
    }

    private fun removeQueuedPagesLocked(predicate: (PriorityPage) -> Boolean) {
        queue.removeIf { queuedPage ->
            predicate(queuedPage).also { remove ->
                if (remove) scheduledPages.remove(queuedPage.page)
            }
        }
    }

    private fun removeQueuedPage(queuedPage: PriorityPage) {
        synchronized(schedulerLock) {
            if (queue.remove(queuedPage)) {
                scheduledPages.remove(queuedPage.page)
            }
        }
    }

    private fun logPrefetchWindow(page: ReaderPage, indexes: List<Int>, reason: String) {
        logcat {
            "MangaStartup: prefetch window updated reason=$reason " +
                "chapterId=${chapter.chapter.id} page=${page.number} " +
                "prefetchPages=${indexes.joinToString { (it + 1).toString() }}"
        }
    }

    /**
     * Loads a page through the queue. Handles re-enqueueing pages if they were evicted from the cache.
     */
    override suspend fun loadPage(page: ReaderPage) = withIOContext {
        val imageUrl = page.imageUrl

        // Check if the image has been deleted
        if (page.status == Page.State.Ready && imageUrl != null && !chapterCache.isImageInCache(imageUrl)) {
            page.status = Page.State.Queue
        }

        // Automatically retry failed pages when subscribed to this page
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }

        val queuedPage = if (page.status == Page.State.Queue && pageLoadGate.isActive(page.index)) {
            preemptPrefetchFor(page, reason = "active-page-load")
            synchronized(schedulerLock) {
                removeQueuedPagesLocked { it.priority == PriorityPage.ADJACENT }
                enqueuePageLocked(page, PriorityPage.DEFAULT)
            }
        } else {
            null
        }

        suspendCancellableCoroutine<Nothing> { continuation ->
            continuation.invokeOnCancellation {
                if (queuedPage != null && queuedPage.page.status == Page.State.Queue) {
                    removeQueuedPage(queuedPage)
                }
            }
        }
    }

    /**
     * Retries a page. This method is only called from user interaction on the viewer.
     */
    override fun retryPage(page: ReaderPage) {
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }
        preemptPrefetchFor(page, reason = "retry")
        synchronized(schedulerLock) {
            enqueuePageLocked(page, PriorityPage.RETRY)
        }
    }

    override fun recycle() {
        super.recycle()
        scope.cancel()
        synchronized(schedulerLock) {
            queue.clear()
            scheduledPages.clear()
            activeLoads.clear()
        }

        // Cache current page list progress for online chapters to allow a faster reopen
        chapter.pages?.let { pages ->
            val chapterSnapshot = domainChapter
            val pagesToSave = pages.map { Page(it.index, it.url, it.imageUrl) }
            pageListCacheWriteScope.launch {
                try {
                    chapterCache.putPageListToCache(chapterSnapshot, pagesToSave)
                } catch (e: Throwable) {
                    if (e is CancellationException) {
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Loads the page, retrieving the image URL and downloading the image if necessary.
     * Downloaded images are stored in the chapter cache.
     *
     * @param page the page whose source image has to be downloaded.
     */
    private suspend fun internalLoadPage(page: ReaderPage, force: Boolean, isPrefetch: Boolean) {
        val startedAt = System.nanoTime()
        try {
            logcat {
                "MangaStartup: page request start chapterId=${chapter.chapter.id} " +
                    "page=${page.number} prefetch=$isPrefetch"
            }
            if (page.imageUrl.isNullOrEmpty()) {
                page.status = Page.State.LoadPage
                page.imageUrl = source.getImageUrl(page)
            }
            val imageUrl = page.imageUrl!!

            val imageInCache = if (force) false else chapterCache.isImageInCache(imageUrl)
            logcat {
                "KohariaOfflineDebug: image cache check " +
                    "chapterId=${chapter.chapter.id} page=${page.number} " +
                    "force=$force imageInCache=$imageInCache imageUrl=$imageUrl"
            }
            if (force || !imageInCache) {
                page.status = Page.State.DownloadImage
                val imageResponse = source.getImage(page)
                chapterCache.putImageToCache(imageUrl, imageResponse)
            }

            page.stream = { chapterCache.getImageFile(imageUrl).inputStream() }
            page.status = Page.State.Ready
            logcat {
                "MangaStartup: page request complete chapterId=${chapter.chapter.id} " +
                    "page=${page.number} prefetch=$isPrefetch " +
                    "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}"
            }
        } catch (e: CancellationException) {
            page.status = Page.State.Queue
            throw e
        } catch (e: Throwable) {
            page.status = Page.State.Error(e)
        }
    }
}

private data class ActivePageLoad(
    val page: ReaderPage,
    val priority: Int,
    val job: Job,
)

/**
 * Data class used to keep ordering of pages in order to maintain priority.
 */
@OptIn(ExperimentalAtomicApi::class)
private class PriorityPage(
    val page: ReaderPage,
    val priority: Int,
) : Comparable<PriorityPage> {
    companion object {
        private val idGenerator = AtomicInt(0)

        const val RETRY = 2
        const val DEFAULT = 1
        const val ADJACENT = 0
    }

    private val identifier = idGenerator.incrementAndFetch()

    override fun compareTo(other: PriorityPage): Int {
        val p = other.priority.compareTo(priority)
        return if (p != 0) p else identifier.compareTo(other.identifier)
    }
}

package koharia.lanraragi.ui

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import koharia.lanraragi.LanraragiTimedBody
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import okio.ForwardingSource
import okio.Source
import okio.buffer
import okio.source
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicBoolean

internal data class LanraragiPreviewImage(
    val manga: Manga,
    val chapter: Chapter,
    val page: Page,
    val downloaded: Boolean,
) {
    val cacheKey get() = "lanraragi-preview:${manga.source}:${chapter.id}:${page.index}:${page.imageUrl}"
}

internal class LanraragiPreviewImageFetcher(
    private val data: LanraragiPreviewImage,
    private val options: Options,
    private val permits: Semaphore,
) : Fetcher {
    override suspend fun fetch(): SourceFetchResult {
        permits.acquire()
        var loader: DownloadPageLoader? = null
        var opened: Source? = null
        val released = AtomicBoolean()
        fun release() {
            if (released.compareAndSet(false, true)) {
                try {
                    loader?.recycle()
                } finally {
                    permits.release()
                }
            }
        }
        try {
            val source = Injekt.get<SourceManager>().get(data.manga.source) as? LanraragiSource
                ?: error("Connection unavailable")
            val downloads = Injekt.get<DownloadManager>()
            val chapter = data.chapter
            val downloaded = data.downloaded || downloads.isChapterDownloaded(
                chapter.name,
                chapter.scanlator,
                chapter.url,
                data.manga.title,
                data.manga.source,
                skipCache = true,
            )
            opened = if (downloaded) {
                val local = DownloadPageLoader(ReaderChapter(chapter), data.manga, source, downloads, Injekt.get())
                loader = local
                val page = local.getPages()[data.page.index]
                checkNotNull(page.stream).invoke().source()
            } else {
                val cache = Injekt.get<ChapterCache>()
                val url = checkNotNull(data.page.imageUrl)
                if (!cache.isImageInCache(url)) {
                    val fetchContext = currentCoroutineContext()
                    source.getImage(data.page).use { response ->
                        val body = LanraragiTimedBody(response.body, { fetchContext.ensureActive() }) { _, _ -> }
                        cache.putImageToCache(url, response.newBuilder().body(body).build())
                    }
                }
                cache.getImageFile(url).source()
            }
            val stream = object : ForwardingSource(opened) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        release()
                    }
                }
            }.buffer()
            return SourceFetchResult(ImageSource(stream, options.fileSystem), null, DataSource.DISK)
        } catch (error: Throwable) {
            try {
                opened?.close()
            } finally {
                release()
            }
            throw error
        }
    }

    class Factory : Fetcher.Factory<LanraragiPreviewImage> {
        private val permits = Semaphore(2)
        override fun create(data: LanraragiPreviewImage, options: Options, imageLoader: ImageLoader): Fetcher =
            LanraragiPreviewImageFetcher(data, options, permits)
    }
}

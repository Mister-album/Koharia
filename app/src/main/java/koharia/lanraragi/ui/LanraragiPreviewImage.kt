package koharia.lanraragi.ui

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.LocalPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import koharia.connection.ConnectionLocalFileAdapter
import koharia.connection.ConnectionSource
import koharia.lanraragi.LanraragiTimedBody
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
    private val sourceManager: SourceManager = Injekt.get(),
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        permits.acquire()
        var recycleLoader: (() -> Unit)? = null
        var opened: Source? = null
        val released = AtomicBoolean()
        fun release() {
            if (released.compareAndSet(false, true)) {
                try {
                    recycleLoader?.invoke()
                } finally {
                    permits.release()
                }
            }
        }
        try {
            val source = sourceManager.get(data.manga.source) as? ConnectionSource
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
                recycleLoader = local::recycle
                val page = local.getPages()[data.page.index]
                checkNotNull(page.stream).invoke().source()
            } else if (source is ConnectionLocalFileAdapter) {
                val local = LocalPageLoader(ReaderChapter(chapter), source, source)
                recycleLoader = local::recycle
                val page = local.getPages()[data.page.index]
                page.bitmap?.let { bitmapProvider ->
                    val bitmap = bitmapProvider()
                    try {
                        currentCoroutineContext().ensureActive()
                        release()
                        return ImageFetchResult(bitmap.asImage(), false, DataSource.DISK)
                    } catch (error: Throwable) {
                        bitmap.recycle()
                        throw error
                    }
                }
                checkNotNull(page.stream).invoke().source()
            } else {
                val cache = Injekt.get<ChapterCache>()
                val url = checkNotNull(data.page.imageUrl)
                if (!cache.isImageInCache(source.id, url)) {
                    val httpSource = source as? HttpSource ?: error("Page preview is unsupported")
                    val fetchContext = currentCoroutineContext()
                    httpSource.getImage(data.page).use { response ->
                        val body = LanraragiTimedBody(response.body, { fetchContext.ensureActive() }) { _, _ -> }
                        cache.putImageToCache(source.id, url, response.newBuilder().body(body).build())
                    }
                }
                cache.getImageFile(source.id, url).source()
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

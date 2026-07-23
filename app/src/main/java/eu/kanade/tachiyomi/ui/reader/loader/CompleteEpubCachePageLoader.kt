package eu.kanade.tachiyomi.ui.reader.loader

import android.os.ParcelFileDescriptor
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import koharia.core.archive.ArchiveReader
import koharia.core.archive.EpubReader
import koharia.epub.cache.EpubCacheManager
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/** Uses the independent EPUB book cache without exposing it as a manual download. */
internal class CompleteEpubCachePageLoader(
    private val file: File,
    private val cacheManager: EpubCacheManager,
    private val expectedPageCount: Int,
) : PageLoader() {

    private var delegate: EpubPageLoader? = null
    private var leaseAcquired = false

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        delegate?.let { return it.getPages() }
        cacheManager.acquire(file)
        leaseAcquired = true
        val loader = try {
            val archive = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                .use { descriptor -> ArchiveReader(descriptor) }
            EpubPageLoader(EpubReader(archive))
        } catch (error: Throwable) {
            releaseLease()
            throw error
        }
        return try {
            val pages = loader.getPages()
            if (pages.size != expectedPageCount) {
                logcat(LogPriority.WARN) {
                    "Cached EPUB page list rejected expected=$expectedPageCount actual=${pages.size}"
                }
                loader.recycle()
                releaseLease()
                emptyList()
            } else {
                delegate = loader
                pages
            }
        } catch (error: Throwable) {
            loader.recycle()
            releaseLease()
            throw error
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        delegate?.loadPage(page)
    }

    override fun recycle() {
        super.recycle()
        delegate?.recycle()
        delegate = null
        releaseLease()
    }

    private fun releaseLease() {
        if (!leaseAcquired) return
        leaseAcquired = false
        cacheManager.release(file)
    }
}

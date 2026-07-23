package eu.kanade.tachiyomi.ui.reader.loader

import android.os.ParcelFileDescriptor
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import koharia.core.archive.ArchiveReader
import koharia.core.archive.EpubReader
import koharia.epub.cache.EpubCacheManager
import java.io.File

/** Uses the independent EPUB book cache without exposing it as a manual download. */
internal class CompleteEpubCachePageLoader(
    private val file: File,
    private val cacheManager: EpubCacheManager,
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
        delegate = loader
        return loader.getPages()
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

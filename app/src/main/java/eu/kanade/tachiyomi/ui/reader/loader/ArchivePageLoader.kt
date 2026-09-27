package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import koharia.core.archive.ArchiveReader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.core.common.util.system.imageEntries

/**
 * Loader used to load a chapter from an archive file.
 */
internal class ArchivePageLoader(private val reader: ArchiveReader) : PageLoader() {
    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val context = currentCoroutineContext()
        return reader.imageEntries { context.ensureActive() }
            .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
            .mapIndexed { i, entry ->
                ReaderPage(i).apply {
                    stream = { reader.getInputStream(entry.name)!! }
                    status = Page.State.Ready
                }
            }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        reader.close()
    }
}

package koharia.epub.service

import koharia.connection.ConnectionEpubProgressAdapter
import koharia.connection.ConnectionLocalEpubProgressAdapter
import koharia.connection.ConnectionReflowProgressAdapter
import koharia.connection.RemoteEpubProgression
import koharia.domain.epub.model.EpubRemoteProgressCache
import koharia.pdf.reflow.PdfProgressMapper
import koharia.pdf.reflow.PdfReflowManifest
import org.readium.r2.shared.publication.Locator
import tachiyomi.domain.chapter.model.Chapter
import java.util.Date

/** Readium locations are translated through the reflow source map before reaching the provider. */
internal class PdfReflowProgressAdapter(
    private val remote: ConnectionReflowProgressAdapter,
    private val chapter: Chapter,
    private val manifest: PdfReflowManifest,
) : ConnectionEpubProgressAdapter, ConnectionLocalEpubProgressAdapter {
    override suspend fun getCachedEpubProgress(chapterId: Long): EpubRemoteProgressCache? = null

    override suspend fun refreshEpubProgress(mangaId: Long, chapter: Chapter): EpubRemoteProgressCache? {
        val progress = pullEpubProgress(chapter.url) ?: return null
        return EpubRemoteProgressCache(
            chapter.id, mangaId, chapter.url, progress.locator.toJSON().toString(),
            page(progress.locator).toDouble() / manifest.pageCount.coerceAtLeast(1), null,
            progress.modifiedAt, Date(), Date(),
        )
    }

    override suspend fun pullEpubProgress(resourceId: String): RemoteEpubProgression? {
        val snapshot = remote.pullPageProgress(resourceId, chapter.memo) ?: return null
        if (snapshot.totalPages != manifest.pageCount || snapshot.requiresPageMappingConfirmation) {
            throw java.io.IOException("PDF page mapping changed")
        }
        val locator = PdfProgressMapper.initial(manifest, null, snapshot.pageIndex ?: 0) ?: return null
        val modified = snapshot.readDate?.let {
            runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
        } ?: 0
        return RemoteEpubProgression(locator, Date(modified))
    }

    private fun page(locator: Locator) = PdfProgressMapper.block(manifest, locator, null)?.source?.page
        ?: throw java.io.IOException("PDF location has no original page")

    override suspend fun recordLocalEpubProgress(resourceId: String, locator: Locator, modifiedAt: Date) {
        remote.recordLocalPageProgress(resourceId, page(locator), manifest.pageCount, modifiedAt.time)
    }
    override suspend fun acceptRemoteEpubProgress(resourceId: String, locator: Locator, modifiedAt: Date) {
        remote.acceptRemotePageProgress(resourceId, page(locator), manifest.pageCount, modifiedAt.time)
    }
    override suspend fun confirmLocalEpubProgress(resourceId: String, locator: Locator, modifiedAt: Date) {
        remote.confirmLocalPageProgress(resourceId, page(locator), manifest.pageCount, modifiedAt.time)
    }
    override suspend fun pushEpubProgress(
        resourceId: String,
        locator: Locator,
        positions: List<Locator>,
        modifiedAt: Date,
    ) {
        remote.pushPageProgress(resourceId, page(locator), manifest.pageCount)
    }
}

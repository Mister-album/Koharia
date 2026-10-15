package koharia.epub.progress

import koharia.connection.ConnectionEpubProgressAdapter
import koharia.connection.ConnectionProgressSession
import koharia.connection.RemoteEpubProgression
import koharia.komga.progress.KomgaProgressOrdering
import koharia.source.komga.KomgaSource
import org.readium.r2.shared.publication.Locator
import tachiyomi.domain.chapter.model.Chapter
import java.util.Date

internal suspend fun captureKomgaEpubProgressSession(
    source: KomgaSource,
    chapter: Chapter,
    service: KomgaEpubProgressSyncService,
    coordinator: KomgaEpubRemoteProgressCoordinator,
    ordering: KomgaProgressOrdering,
): ConnectionProgressSession<ConnectionEpubProgressAdapter> {
    val session = service.captureSession(source)
    val token = ordering.capture(KomgaProgressOrdering.key(source.id, session.account.key, chapter.url))
    val checkCurrent: suspend () -> Unit = {
        if (koharia.connection.ConnectionRestoreState.isRestoring) throw java.io.IOException("Restore in progress")
        session.guard.requireCurrent()
        ordering.checkCurrent(token)
    }
    fun checkResource(url: String) {
        require(KomgaProgressOrdering.key(source.id, session.account.key, url) == token.key) {
            "EPUB progress resource changed"
        }
    }
    checkCurrent()
    val adapter = object : ConnectionEpubProgressAdapter {
        override suspend fun getCachedEpubProgress(chapterId: Long) =
            ordering.remote(token, session.guard::requireCurrent) {
                require(chapterId == chapter.id)
                coordinator.get(chapterId, session, checkCurrent)
            }

        override suspend fun refreshEpubProgress(mangaId: Long, chapter: Chapter) =
            ordering.remote(token, session.guard::requireCurrent) {
                checkResource(chapter.url)
                coordinator.syncManga(mangaId, session, listOf(chapter), true, true, checkCurrent).firstOrNull()
            }

        override suspend fun syncMangaEpubProgress(mangaId: Long, chapters: List<Chapter>, force: Boolean) =
            ordering.remote(token, session.guard::requireCurrent) {
                chapters.forEach { checkResource(it.url) }
                coordinator.syncManga(mangaId, session, chapters, force, checkCurrent = checkCurrent)
            }

        override suspend fun pullEpubProgress(resourceId: String): RemoteEpubProgression? =
            ordering.remote(token, session.guard::requireCurrent) {
                checkResource(resourceId)
                service.pullProgression(session, resourceId, checkCurrent).progression?.let {
                    RemoteEpubProgression(it.locator, it.modifiedAt)
                }
            }

        override suspend fun pushEpubProgress(
            resourceId: String,
            locator: Locator,
            positions: List<Locator>,
            modifiedAt: Date,
        ) = ordering.remote(token, session.guard::requireCurrent) {
            checkResource(resourceId)
            service.pushProgression(session, resourceId, locator, positions, modifiedAt, checkCurrent)
        }

        override suspend fun reviewEpubProgress(resourceId: String) = pullEpubProgress(resourceId)
    }
    return ConnectionProgressSession(adapter, checkCurrent)
}

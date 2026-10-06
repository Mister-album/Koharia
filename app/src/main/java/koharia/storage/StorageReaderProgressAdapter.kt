package koharia.storage

import com.hippo.unifile.RemoteStorageFile
import com.hippo.unifile.UniFile
import koharia.connection.ConnectionEpubProgressAdapter
import koharia.connection.ConnectionLocalEpubProgressAdapter
import koharia.connection.ConnectionLocalPageProgressAdapter
import koharia.connection.ConnectionPageProgressAdapter
import koharia.connection.ConnectionPageProgressSnapshot
import koharia.connection.ConnectionReadStatusAdapter
import koharia.connection.RemoteEpubProgression
import koharia.domain.epub.model.EpubRemoteProgressCache
import kotlinx.serialization.json.JsonObject
import org.readium.r2.shared.publication.Locator
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

class StorageReaderProgressAdapter(private val resolve: suspend (String) -> UniFile?) :
    ConnectionPageProgressAdapter,
    ConnectionLocalPageProgressAdapter,
    ConnectionEpubProgressAdapter,
    ConnectionLocalEpubProgressAdapter,
    ConnectionReadStatusAdapter {
    private suspend fun target(url: String): Pair<RemoteStorageFile, StorageEntry>? {
        val file = resolve(url) as? RemoteStorageFile ?: return null
        return file to (file.runtime.cached(file.storagePath) ?: return null)
    }
    private suspend fun key(file: RemoteStorageFile, entry: StorageEntry) = file.runtime.identities.identity(entry)

    override suspend fun pullPageProgress(
        chapterUrl: String,
        chapterMemo: JsonObject,
    ): ConnectionPageProgressSnapshot? {
        val (file, entry) = target(chapterUrl) ?: return null
        val event = file.runtime.progress.pull(key(file, entry), entry.version) ?: return null
        return ConnectionPageProgressSnapshot(
            chapterUrl, event.page, event.total, event.completed,
            java.time.Instant.ofEpochMilli(event.modifiedAt).toString(),
            false, true, chapterMemo, entry.version, entry.version,
            requiresConfirmation = false,
        )
    }
    override suspend fun recordLocalPageProgress(
        chapterUrl: String,
        pageIndex: Int,
        totalPages: Int,
        readAt: Long,
        initialPage: Boolean,
    ) {
        if (initialPage) return
        val (file, entry) = target(chapterUrl) ?: return
        file.runtime.progress.record(
            key(file, entry),
            entry.version,
            readAt,
            pageIndex,
            totalPages,
            completed = totalPages > 0 && pageIndex >= totalPages - 1,
        )
    }
    override suspend fun pushPageProgress(chapterUrl: String, pageIndex: Int, totalPages: Int) {
        target(chapterUrl)?.first?.runtime?.progress?.flush()
    }
    override suspend fun setChapterReadStatus(chapterUrl: String, read: Boolean) {
        val (file, entry) = target(chapterUrl) ?: return
        val total = file.runtime.progress.cached(key(file, entry))?.total ?: 0
        file.runtime.progress.record(
            key(file, entry),
            entry.version,
            System.currentTimeMillis(),
            page = if (read) (total - 1).coerceAtLeast(0) else 0,
            total = total,
            completed = read,
        )
        file.runtime.progress.flush()
    }
    override suspend fun getCachedEpubProgress(chapterId: Long): EpubRemoteProgressCache? {
        val chapter = Injekt.get<GetChapter>().await(chapterId) ?: return null
        val (file, entry) = target(chapter.url) ?: return null
        val event = file.runtime.progress.cached(key(file, entry)) ?: return null
        if (event.version != entry.version) return null
        return cache(chapter, event)
    }
    override suspend fun refreshEpubProgress(mangaId: Long, chapter: Chapter): EpubRemoteProgressCache? {
        val (file, entry) = target(chapter.url) ?: return null
        val event = file.runtime.progress.pull(key(file, entry), entry.version) ?: return null
        return cache(chapter, event)
    }
    private fun cache(chapter: Chapter, event: StorageProgressRecord): EpubRemoteProgressCache? {
        val locator =
            event.locator?.let { runCatching { Locator.fromJSON(org.json.JSONObject(it)) }.getOrNull() } ?: return null
        return EpubRemoteProgressCache(
            chapter.id, chapter.mangaId, chapter.url, event.locator,
            locator.locations.totalProgression, locator.locations.position?.toLong(),
            Date(
                event.modifiedAt,
            ),
            Date(), null,
        )
    }
    override suspend fun pullEpubProgress(resourceId: String): RemoteEpubProgression? {
        val (file, entry) = target(resourceId) ?: return null
        val event = file.runtime.progress.pull(key(file, entry), entry.version) ?: return null
        val locator =
            event.locator?.let { runCatching { Locator.fromJSON(org.json.JSONObject(it)) }.getOrNull() } ?: return null
        return RemoteEpubProgression(locator, Date(event.modifiedAt))
    }
    override suspend fun pushEpubProgress(
        resourceId: String,
        locator: Locator,
        positions: List<Locator>,
        modifiedAt: Date,
    ) {
        target(resourceId)?.first?.runtime?.progress?.flush()
    }
    override suspend fun recordLocalEpubProgress(resourceId: String, locator: Locator, modifiedAt: Date) {
        val (file, entry) = target(resourceId) ?: return
        file.runtime.progress.record(
            key(file, entry),
            entry.version,
            modifiedAt.time,
            completed = (locator.locations.totalProgression ?: 0.0) >= 1.0,
            locator = locator.toJSON().toString(),
        )
    }
    override suspend fun acceptRemoteEpubProgress(resourceId: String, locator: Locator, modifiedAt: Date) = Unit
    override suspend fun confirmLocalEpubProgress(resourceId: String, locator: Locator, modifiedAt: Date) =
        recordLocalEpubProgress(resourceId, locator, modifiedAt)
}

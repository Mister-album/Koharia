package koharia.storage

import com.hippo.unifile.RemoteStorageFile
import com.hippo.unifile.UniFile
import koharia.connection.ConnectionEpubProgressAdapter
import koharia.connection.ConnectionLocalEpubProgressAdapter
import koharia.connection.ConnectionPageProgressSnapshot
import koharia.connection.ConnectionProgressSession
import koharia.connection.ConnectionReadStatus
import koharia.connection.ConnectionReadStatusAdapter
import koharia.connection.RemoteEpubProgression
import koharia.connection.readStatusFailure
import koharia.domain.epub.model.EpubRemoteProgressCache
import kotlinx.serialization.json.JsonObject
import org.readium.r2.shared.publication.Locator
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

class StorageReaderProgressAdapter(
    private val checkCurrent: () -> Unit = {},
    private val openedEntry: StorageEntry? = null,
    private val entryCaptured: Boolean = false,
    private val resolve: suspend (String) -> UniFile?,
) :
    koharia.connection.ConnectionReaderPageProgressAdapter,
    ConnectionEpubProgressAdapter,
    ConnectionLocalEpubProgressAdapter,
    ConnectionReadStatusAdapter {
    private val reviews = java.util.concurrent.ConcurrentHashMap<String, StorageProgressReview>()

    suspend fun captureEpubProgressSession(
        chapter: Chapter,
        checkOwner: () -> Unit,
    ): ConnectionProgressSession<StorageReaderProgressAdapter> {
        checkOwner()
        val file = resolve(chapter.url)
        checkOwner()
        val remote = file as? RemoteStorageFile
        val path = remote?.storagePath
        val entry = remote?.runtime?.cached(remote.storagePath)
        val check = {
            checkOwner()
            remote?.runtime?.session?.checkActive()
            if (remote?.storagePath != path) throw StorageFailure(StorageFailure.Reason.CONFLICT)
            Unit
        }
        check()
        return ConnectionProgressSession(
            StorageReaderProgressAdapter(
                resolve = { url ->
                    require(url == chapter.url) { "Reading resource changed" }
                    file
                },
                checkCurrent = check,
                openedEntry = entry,
                entryCaptured = true,
            ),
            check,
        )
    }

    suspend fun reflowProgressAdapter(url: String): koharia.connection.ConnectionReflowProgressAdapter? {
        checkCurrent()
        val resolved = resolve(url) ?: throw StorageFailure(StorageFailure.Reason.NOT_FOUND)
        checkCurrent()
        val file = resolved as? RemoteStorageFile ?: return null
        if (!file.runtime.config.persistentIdentity) return null
        return PdfProgress(this)
    }

    private fun checkAcceptance(file: RemoteStorageFile) {
        checkCurrent()
        if (Injekt.get<koharia.connection.ConnectionIdentityChanges>().isPaused(file.runtime.connectionId)) {
            throw koharia.connection.ConnectionWritesPaused()
        }
    }

    private inner class PdfProgress(delegate: koharia.connection.ConnectionReaderPageProgressAdapter) :
        koharia.connection.ConnectionReflowProgressAdapter,
        koharia.connection.ConnectionReaderPageProgressAdapter by delegate {
        private var reviewed: StorageProgressReview? = null

        override suspend fun pullPageProgress(chapterUrl: String, chapterMemo: JsonObject) =
            reviewPageProgress(chapterUrl, chapterMemo)

        override suspend fun reviewPageProgress(
            chapterUrl: String,
            chapterMemo: JsonObject,
        ): ConnectionPageProgressSnapshot {
            reviewed = null
            val (file, entry) = target(chapterUrl) ?: throw StorageFailure(StorageFailure.Reason.NOT_FOUND)
            val review = file.runtime.progress.reviewSnapshot(key(file, entry), entry.version)
                ?: throw StorageFailure(StorageFailure.Reason.UNVERIFIED)
            val event = review.remote
            if (event?.locator != null) throw StorageFailure(StorageFailure.Reason.CONFLICT)
            checkCurrent()
            reviewed = review
            val local = review.localProgress
            return ConnectionPageProgressSnapshot(
                chapterUrl, event?.page, event?.total ?: 0, event?.completed ?: false,
                event?.modifiedAt?.let { java.time.Instant.ofEpochMilli(it).toString() },
                false, true, chapterMemo, local?.record?.version ?: entry.version, entry.version,
                requiresConfirmation = false,
                requiresPageMappingConfirmation = local?.pending == true && local.record.version != entry.version,
                blocksAutomaticSelection = local?.pending == true && event != null,
            )
        }

        override suspend fun acceptRemotePageProgress(
            chapterUrl: String,
            pageIndex: Int,
            totalPages: Int,
            readAt: Long,
        ) {
            val (file, entry) = target(chapterUrl) ?: throw StorageFailure(StorageFailure.Reason.NOT_FOUND)
            val review = reviewed.also { reviewed = null }
            file.runtime.progress.acceptRemotePage(
                key(file, entry),
                entry.version,
                pageIndex,
                totalPages,
                readAt,
                review,
            ) {
                checkAcceptance(file)
            }
            checkCurrent()
        }

        override suspend fun confirmLocalPageProgress(
            chapterUrl: String,
            pageIndex: Int,
            totalPages: Int,
            readAt: Long,
        ) {
            val (file, entry) = target(chapterUrl) ?: throw StorageFailure(StorageFailure.Reason.NOT_FOUND)
            val review = reviewed.also { reviewed = null }
            file.runtime.progress.confirmLocal(
                key(file, entry),
                entry.version,
                readAt,
                review,
                { checkAcceptance(file) },
                page = pageIndex,
                total = totalPages,
                completed = pageIndex == totalPages - 1,
            )
            checkCurrent()
        }

        override suspend fun isPageProgressConfirmed(
            chapterUrl: String,
            page: Int,
            total: Int,
            modifiedAt: Long,
        ): Boolean {
            val (file, entry) = target(chapterUrl) ?: return false
            return file.runtime.progress.isPageConfirmed(key(file, entry), entry.version, page, total, modifiedAt)
                .also { checkCurrent() }
        }
    }

    private suspend fun target(url: String): Pair<RemoteStorageFile, StorageEntry>? {
        checkCurrent()
        val file = resolve(url) as? RemoteStorageFile ?: return null
        val entry = file.runtime.cached(file.storagePath)
        checkCurrent()
        if (entryCaptured && entry != openedEntry) throw StorageFailure(StorageFailure.Reason.CONFLICT)
        return entry?.let { file to it }
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
    override suspend fun setChapterReadStatus(chapterUrl: String, read: Boolean): ConnectionReadStatus {
        val resolved = resolve(chapterUrl) ?: return ConnectionReadStatus.UNAVAILABLE
        if (resolved !is RemoteStorageFile) return ConnectionReadStatus.LOCAL_ONLY
        val file = resolved
        val entry = file.runtime.cached(file.storagePath) ?: return ConnectionReadStatus.UNAVAILABLE
        val resource = key(file, entry)
        val total = file.runtime.progress.cached(resource)?.total ?: 0
        file.runtime.progress.record(
            resource,
            entry.version,
            System.currentTimeMillis(),
            page = if (read) (total - 1).coerceAtLeast(0) else 0,
            total = total,
            completed = read,
        )
        try {
            file.runtime.progress.flush()
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (error is StorageFailure && error.reason == StorageFailure.Reason.CONFLICT) {
                return ConnectionReadStatus.CONFLICT
            }
            if (error.readStatusFailure() == ConnectionReadStatus.DENIED) return ConnectionReadStatus.DENIED
            // The local record is durable; a transport failure must not turn it into a reported success.
            return file.runtime.progress.readStatus(resource)
        }
        return file.runtime.progress.readStatus(resource)
    }
    override suspend fun getCachedEpubProgress(chapterId: Long): EpubRemoteProgressCache? {
        checkCurrent()
        val chapter = Injekt.get<GetChapter>().await(chapterId) ?: return null
        val (file, entry) = target(chapter.url) ?: return null
        val event = file.runtime.progress.cached(key(file, entry)) ?: return null
        if (event.version != entry.version) return null
        return cache(chapter, event)
    }
    override suspend fun refreshEpubProgress(mangaId: Long, chapter: Chapter): EpubRemoteProgressCache? {
        val (file, entry) = target(chapter.url) ?: return null
        val event = file.runtime.progress.pull(key(file, entry), entry.version)
        checkCurrent()
        if (event != null) return cache(chapter, event)
        if (!file.runtime.config.persistentIdentity) return null
        return EpubRemoteProgressCache(
            chapterId = chapter.id,
            mangaId = mangaId,
            bookUrl = chapter.url,
            locatorJson = null,
            progression = null,
            positionIndex = null,
            modifiedAt = null,
            checkedAt = Date(),
            serverDate = null,
        )
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
    override suspend fun reviewEpubProgress(resourceId: String): RemoteEpubProgression? {
        val (file, entry) = target(resourceId) ?: return null
        reviews.remove(resourceId)
        val review = file.runtime.progress.reviewSnapshot(key(file, entry), entry.version) ?: return null
        val event = review.remote ?: return null
        val locator =
            event.locator?.let { runCatching { Locator.fromJSON(org.json.JSONObject(it)) }.getOrNull() } ?: return null
        checkCurrent()
        reviews[resourceId] = review
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
    override suspend fun acceptRemoteEpubProgress(
        resourceId: String,
        locator: Locator,
        modifiedAt: Date,
    ) {
        val (file, entry) = target(resourceId) ?: throw StorageFailure(StorageFailure.Reason.UNSUPPORTED)
        val checkAcceptance = { checkAcceptance(file) }
        checkAcceptance()
        file.runtime.progress.acceptRemote(
            key(file, entry),
            entry.version,
            modifiedAt.time,
            reviews.remove(resourceId),
            checkAcceptance,
        ) { encoded ->
            runCatching { Locator.fromJSON(org.json.JSONObject(encoded)) }.getOrNull() == locator
        }
        checkCurrent()
    }
    override suspend fun confirmLocalEpubProgress(resourceId: String, locator: Locator, modifiedAt: Date) {
        val (file, entry) = target(resourceId) ?: throw StorageFailure(StorageFailure.Reason.UNSUPPORTED)
        file.runtime.progress.confirmLocal(
            key(file, entry),
            entry.version,
            modifiedAt.time,
            reviews.remove(resourceId),
            { checkAcceptance(file) },
            completed = (locator.locations.totalProgression ?: 0.0) >= 1.0,
            locator = locator.toJSON().toString(),
        )
        checkCurrent()
    }

    override suspend fun isEpubProgressConfirmed(resourceId: String, locator: Locator, modifiedAt: Date): Boolean {
        val (file, entry) = target(resourceId) ?: return false
        return file.runtime.progress.isEpubConfirmed(
            key(file, entry),
            entry.version,
            locator.toJSON().toString(),
            modifiedAt.time,
        )
            .also { checkCurrent() }
    }
}

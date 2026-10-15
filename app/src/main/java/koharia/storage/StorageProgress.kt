package koharia.storage

import koharia.connection.ConnectionReadStatus
import koharia.domain.storage.StorageRecord
import koharia.domain.storage.StorageRecordUpdate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID

@Serializable
data class StorageProgressRecord(
    val resource: String,
    val version: String,
    val device: String,
    val sequence: Long,
    val modifiedAt: Long,
    val page: Int? = null,
    val total: Int = 0,
    val completed: Boolean = false,
    val locator: String? = null,
    val schema: Int = 1,
)

@Serializable
data class StoragePendingProgress(
    val record: StorageProgressRecord,
    val pending: Boolean,
    val requiresConfirmation: Boolean = false,
)

fun latestStorageProgress(
    records: Iterable<StorageProgressRecord>,
    resource: String,
    version: String,
): StorageProgressRecord? =
    records.filter {
        it.schema == 1 && it.resource == resource && it.version == version && it.sequence >= 0 &&
            it.modifiedAt >= 0 && it.total >= 0 && (it.page == null || it.page >= 0) &&
            it.device.isNotBlank() && it.device.length <= 128
    }
        .maxWithOrNull(compareBy<StorageProgressRecord> { it.modifiedAt }.thenBy { it.device }.thenBy { it.sequence })

/** Evidence belongs to one runtime and the exact local state observed during remote review. */
class StorageProgressReview internal constructor(
    internal val owner: StorageProgress,
    internal val resource: String,
    internal val version: String,
    internal val local: StorageRecord?,
    val remote: StorageProgressRecord?,
    val localProgress: StoragePendingProgress?,
)

/** Independent device records avoid replacing another device's writes. Local events are durable before upload. */
class StorageProgress(
    private val backend: LibraryStorageBackend,
    private val store: StorageRecordStore,
    private val installation: String,
    private val remoteEnabled: Boolean = true,
) {
    private val mutex = Mutex()

    // Legacy unscoped remote files have no reliable account owner; leave them untouched.
    private fun resourcePrefix(resource: String) = "${storageDigest(store.session.account)}-${storageDigest(resource)}-"
    suspend fun record(
        resource: String,
        version: String,
        modifiedAt: Long,
        page: Int? = null,
        total: Int = 0,
        completed: Boolean = false,
        locator: String? = null,
    ) = mutex.withLock {
        recordLocked(resource, version, modifiedAt, page, total, completed, locator)
    }

    private suspend fun recordLocked(
        resource: String,
        version: String,
        modifiedAt: Long,
        page: Int?,
        total: Int,
        completed: Boolean,
        locator: String?,
        review: StorageProgressReview? = null,
        checkAcceptance: () -> Unit = {},
    ): StorageProgressRecord {
        val device = writer()
        var event: StorageProgressRecord
        var committed: Boolean
        do {
            currentCoroutineContext().ensureActive()
            val snapshot = store.snapshot("progress", resource)
            if (review != null && snapshot != review.local) throw StorageFailure(StorageFailure.Reason.CONFLICT)
            val previous = snapshot?.let { decodeStoragePendingProgress(it, store.json) }
            val counter = store.snapshot("progress-device", device)
            val deviceSequence = counter?.let { store.json.decodeFromString<Long>(it.payload) } ?: 0
            val sequence = maxOf(previous?.record?.sequence ?: 0, deviceSequence)
            require(sequence >= 0 && sequence < Long.MAX_VALUE)
            event = StorageProgressRecord(
                resource, version, device, sequence + 1,
                modifiedAt, page, total, completed, locator,
            )
            val held = previous?.requiresConfirmation == true
            committed = store.compareAndSet(
                listOf(
                    StorageRecordUpdate(
                        "progress",
                        snapshot,
                        StorageRecord(
                            resource,
                            store.json.encodeToString(StoragePendingProgress(event, remoteEnabled || held, held)),
                            event.sequence,
                        ),
                    ),
                    StorageRecordUpdate(
                        "progress-device",
                        counter,
                        StorageRecord(device, store.json.encodeToString(event.sequence), event.sequence),
                    ),
                ),
                checkAcceptance,
            )
            if (!committed && review != null) throw StorageFailure(StorageFailure.Reason.CONFLICT)
        } while (!committed)
        return event
    }

    /** A local owner has its own counter lifetime, including after deletion and recreation. */
    private suspend fun writer(): String {
        while (true) {
            currentCoroutineContext().ensureActive()
            val existing = store.get<String>(WRITER_NAMESPACE, installation)
            if (existing != null) {
                require(UUID.fromString(existing).toString() == existing)
                return existing
            }
            val candidate = UUID.randomUUID().toString()
            if (store.compareAndSet(
                    listOf(
                        StorageRecordUpdate(
                            WRITER_NAMESPACE,
                            null,
                            StorageRecord(installation, store.json.encodeToString(candidate), 1),
                        ),
                    ),
                )
            ) {
                return candidate
            }
        }
    }

    suspend fun cached(resource: String): StorageProgressRecord? = store.get<StoragePendingProgress>(
        "progress",
        resource,
    )?.record

    suspend fun isEpubConfirmed(resource: String, version: String, locator: String, modifiedAt: Long): Boolean {
        val item = store.snapshot("progress", resource)?.let { decodeStoragePendingProgress(it, store.json) }
            ?: return false
        return remoteEnabled && !item.pending && !item.requiresConfirmation &&
            item.record.version == version && item.record.locator == locator && item.record.modifiedAt == modifiedAt
    }

    suspend fun readStatus(resource: String): ConnectionReadStatus {
        val item = store.get<StoragePendingProgress>("progress", resource)
            ?: return ConnectionReadStatus.UNAVAILABLE
        return when {
            item.requiresConfirmation -> ConnectionReadStatus.HELD
            !remoteEnabled -> ConnectionReadStatus.LOCAL_ONLY
            item.pending -> ConnectionReadStatus.PENDING
            else -> ConnectionReadStatus.SYNCED
        }
    }

    suspend fun pull(resource: String, version: String): StorageProgressRecord? = mutex.withLock {
        val snapshot = store.snapshot("progress", resource)
        val local = snapshot?.let { decodeStoragePendingProgress(it, store.json) }
        if (!remoteEnabled || local?.requiresConfirmation == true) {
            return@withLock latestStorageProgress(listOfNotNull(local?.record), resource, version)
        }
        val records = remoteRecords(resource).toMutableList()
        local?.record?.let(records::add)
        val winner = latestStorageProgress(records, resource, version)
        val unchanged = if (winner != null && winner != local?.record) {
            store.compareAndSet(
                listOf(
                    StorageRecordUpdate(
                        "progress",
                        snapshot,
                        StorageRecord(
                            resource,
                            store.json.encodeToString(StoragePendingProgress(winner, false)),
                            winner.sequence,
                        ),
                    ),
                ),
            )
        } else {
            store.snapshot("progress", resource) == snapshot
        }
        if (unchanged) {
            winner
        } else {
            val current = store.snapshot("progress", resource)?.let { decodeStoragePendingProgress(it, store.json) }
            latestStorageProgress(listOfNotNull(current?.record), resource, version)
        }
    }

    suspend fun review(resource: String, version: String): StorageProgressRecord? =
        reviewSnapshot(resource, version)?.remote

    suspend fun reviewSnapshot(resource: String, version: String): StorageProgressReview? = mutex.withLock {
        val snapshot = store.snapshot("progress", resource)
        val local = snapshot?.let { decodeStoragePendingProgress(it, store.json) }
        if (!remoteEnabled || local?.requiresConfirmation == true) return@withLock null
        val remote = latestStorageProgress(remoteRecords(resource), resource, version)
        check(store.snapshot("progress", resource) == snapshot) { "Storage progress changed during review" }
        StorageProgressReview(this, resource, version, snapshot, remote, local)
    }

    suspend fun acceptRemote(
        resource: String,
        version: String,
        modifiedAt: Long,
        review: StorageProgressReview?,
        checkAcceptance: () -> Unit,
        matchesLocator: (String) -> Boolean,
    ) = acceptReviewed(resource, version, review, checkAcceptance) {
        it.modifiedAt == modifiedAt && it.locator?.let(matchesLocator) == true
    }

    suspend fun acceptRemotePage(
        resource: String,
        version: String,
        page: Int,
        total: Int,
        modifiedAt: Long,
        review: StorageProgressReview?,
        checkAcceptance: () -> Unit,
    ) {
        require(total > 0 && page in 0 until total)
        acceptReviewed(resource, version, review, checkAcceptance) {
            it.locator == null && it.total == total && it.modifiedAt == modifiedAt &&
                (it.page ?: if (it.completed) total - 1 else 0) == page
        }
    }

    private suspend fun checkReview(
        resource: String,
        version: String,
        review: StorageProgressReview?,
        checkAcceptance: () -> Unit,
    ): StorageProgressReview {
        checkAcceptance()
        if (!remoteEnabled) throw StorageFailure(StorageFailure.Reason.UNSUPPORTED)
        if (review == null || review.owner !== this || review.resource != resource || review.version != version) {
            throw StorageFailure(StorageFailure.Reason.CONFLICT)
        }
        val snapshot = review.local
        if (store.snapshot("progress", resource) != snapshot) throw StorageFailure(StorageFailure.Reason.CONFLICT)
        val local = review.localProgress
        if (local?.requiresConfirmation == true) throw StorageFailure(StorageFailure.Reason.UNVERIFIED)
        if (local?.pending == true && local.record.version != version) {
            throw StorageFailure(StorageFailure.Reason.CONFLICT)
        }
        val remote = latestStorageProgress(remoteRecords(resource), resource, version)
        if (remote != review.remote) throw StorageFailure(StorageFailure.Reason.CONFLICT)
        return review
    }

    private suspend fun acceptReviewed(
        resource: String,
        version: String,
        review: StorageProgressReview?,
        checkAcceptance: () -> Unit,
        matches: (StorageProgressRecord) -> Boolean,
    ) = mutex.withLock {
        val compared = checkReview(resource, version, review, checkAcceptance)
        val remote = compared.remote ?: throw StorageFailure(StorageFailure.Reason.CONFLICT)
        if (!matches(remote)) throw StorageFailure(StorageFailure.Reason.CONFLICT)
        if (!store.compareAndSet(
                listOf(
                    StorageRecordUpdate(
                        "progress",
                        compared.local,
                        StorageRecord(
                            resource,
                            store.json.encodeToString(StoragePendingProgress(remote, false)),
                            remote.sequence,
                        ),
                    ),
                ),
                checkAcceptance,
            )
        ) {
            throw StorageFailure(StorageFailure.Reason.CONFLICT)
        }
    }

    suspend fun confirmLocal(
        resource: String,
        version: String,
        modifiedAt: Long,
        review: StorageProgressReview?,
        checkAcceptance: () -> Unit,
        page: Int? = null,
        total: Int = 0,
        completed: Boolean = false,
        locator: String? = null,
    ) = mutex.withLock {
        val compared = checkReview(resource, version, review, checkAcceptance)
        if (page != null) {
            require(total > 0 && page in 0 until total)
            if (compared.remote?.let { it.locator != null || (it.total > 0 && it.total != total) } == true) {
                throw StorageFailure(StorageFailure.Reason.CONFLICT)
            }
        }
        recordLocked(resource, version, modifiedAt, page, total, completed, locator, compared, checkAcceptance)
    }

    suspend fun isPageConfirmed(resource: String, version: String, page: Int, total: Int, modifiedAt: Long): Boolean {
        if (total <= 0 || page !in 0 until total) return false
        val item = store.snapshot("progress", resource)?.let { decodeStoragePendingProgress(it, store.json) }
            ?: return false
        return remoteEnabled && !item.pending && !item.requiresConfirmation &&
            item.record.version == version && item.record.locator == null &&
            (item.record.page ?: if (item.record.completed) total - 1 else 0) == page &&
            item.record.total == total && item.record.modifiedAt == modifiedAt
    }

    private suspend fun remoteRecords(resource: String): List<StorageProgressRecord> {
        store.session.checkActive()
        val prefix = resourcePrefix(resource)
        val entries = try {
            backend.list(DIRECTORY)
        } catch (error: StorageFailure) {
            if (error.reason != StorageFailure.Reason.NOT_FOUND) throw error
            emptyList()
        }
        val records = mutableListOf<StorageProgressRecord>()
        for (entry in entries.filter { !it.directory && it.name.startsWith(prefix) && it.name.endsWith(".json") }) {
            if (entry.size !in 1..MAX_RECORD_SIZE) continue
            val bytes = backend.read(entry, 0, entry.size.toInt())
            val record =
                runCatching { store.json.decodeFromString<StorageProgressRecord>(bytes.decodeToString()) }.getOrNull()
                    ?: continue
            records += record
        }
        store.session.checkActive()
        return records
    }

    suspend fun flush() = mutex.withLock {
        if (!remoteEnabled) return@withLock
        val pending = store.list("progress").mapNotNull { row ->
            try {
                decodeStoragePendingProgress(row, store.json).takeIf { item ->
                    item.pending && !item.requiresConfirmation
                }?.let { row to it }
            } catch (_: IllegalArgumentException) {
                // Retain unreadable records for review without blocking unrelated valid updates.
                null
            }
        }
        if (pending.isEmpty()) return@withLock
        var directoryReady = false
        var historyByResource: Map<String?, List<StorageEntry>>? = null
        for ((row, item) in pending) {
            if (!store.matchesPayload("progress", row)) continue
            if (!directoryReady) {
                ensureDirectory(DIRECTORY)
                directoryReady = true
            }
            if (!store.matchesPayload("progress", row)) continue
            val event = item.record
            val path = "$DIRECTORY/${resourcePrefix(event.resource)}${event.device}-${event.sequence}.json"
            val bytes = store.json.encodeToString(event).encodeToByteArray()
            require(bytes.size <= MAX_RECORD_SIZE)
            try {
                backend.write(path, bytes.inputStream(), bytes.size.toLong())
            } catch (error: StorageFailure) {
                if (error.reason != StorageFailure.Reason.CONFLICT) throw error
                val matches = try {
                    val existing = backend.stat(path)
                    existing.size == bytes.size.toLong() &&
                        backend.read(existing, 0, bytes.size).contentEquals(bytes)
                } catch (verificationError: Exception) {
                    if (verificationError is kotlinx.coroutines.CancellationException) throw verificationError
                    false
                }
                if (!matches) throw error
            }
            if (store.replaceIfUnchanged("progress", row, item.copy(pending = false))) {
                // Legacy queued events keep their writer; do not prune another connection's old stream.
                if (event.device != store.get<String>(WRITER_NAMESPACE, installation)) continue
                // A flush has one pending event per resource. Later arrivals can wait for the next prune.
                val history = historyByResource ?: backend.list(DIRECTORY).groupBy {
                    RESOURCE_PREFIX.find(it.name)?.value
                }.also { historyByResource = it }
                pruneOwnHistory(event, path, history[resourcePrefix(event.resource)].orEmpty())
            }
        }
    }

    /** Keep other writers and content versions intact; only remove records dominated by a durable upload. */
    private suspend fun pruneOwnHistory(event: StorageProgressRecord, savedPath: String, history: List<StorageEntry>) {
        val prefix = "${resourcePrefix(event.resource)}${event.device}-"
        for (entry in history) {
            if (entry.directory || entry.path == savedPath || !entry.name.startsWith(prefix) ||
                !entry.name.endsWith(".json") || entry.size !in 1..MAX_RECORD_SIZE
            ) {
                continue
            }
            val older = try {
                store.json.decodeFromString<StorageProgressRecord>(
                    backend.read(entry, 0, entry.size.toInt()).decodeToString(),
                )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                continue
            }
            if (older.resource == event.resource && older.device == event.device &&
                older.sequence < event.sequence && older.version == event.version &&
                latestStorageProgress(listOf(older, event), event.resource, event.version) == event
            ) {
                backend.delete(entry)
            }
        }
    }

    private suspend fun ensureDirectory(path: String) {
        try {
            if (backend.stat(path).directory) return
        } catch (error: StorageFailure) {
            if (error.reason != StorageFailure.Reason.NOT_FOUND) throw error
        }
        try {
            backend.createDirectory(path)
        } catch (error: StorageFailure) {
            if (error.reason != StorageFailure.Reason.CONFLICT || !backend.stat(path).directory) throw error
        }
    }
    companion object {
        // Remote names begin with the account and resource SHA-256 digests, independent of the writer suffix.
        private val RESOURCE_PREFIX = Regex("^[0-9a-f]{64}-[0-9a-f]{64}-")
        private const val DIRECTORY = ".koharia/progress"
        private const val MAX_RECORD_SIZE = 64 * 1024L
        private const val WRITER_NAMESPACE = "progress-writer"
        fun deviceId(context: android.content.Context): String {
            val preferences = context.getSharedPreferences(
                "storage_device_identity",
                android.content.Context.MODE_PRIVATE,
            )
            synchronized(StorageProgress::class.java) {
                return preferences.getString("id", null) ?: UUID.randomUUID().toString().also {
                    check(preferences.edit().putString("id", it).commit())
                }
            }
        }
    }
}

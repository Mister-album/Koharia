package koharia.storage

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
data class StoragePendingProgress(val record: StorageProgressRecord, val pending: Boolean)

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

/** Independent device records avoid replacing another device's writes. Local events are durable before upload. */
class StorageProgress(
    private val backend: LibraryStorageBackend,
    private val store: StorageRecordStore,
    private val device: String,
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
        val previous = store.get<StoragePendingProgress>("progress", resource)
        val event = StorageProgressRecord(
            resource, version, device,
            maxOf(previous?.record?.sequence ?: 0, store.get<Long>("progress-device", device) ?: 0) + 1,
            modifiedAt, page, total, completed, locator,
        )
        store.put("progress", resource, StoragePendingProgress(event, remoteEnabled), event.sequence)
        store.put("progress-device", device, event.sequence)
        event
    }

    suspend fun cached(resource: String): StorageProgressRecord? = store.get<StoragePendingProgress>(
        "progress",
        resource,
    )?.record

    suspend fun pull(resource: String, version: String): StorageProgressRecord? = mutex.withLock {
        val local = store.get<StoragePendingProgress>("progress", resource)
        if (!remoteEnabled) return@withLock latestStorageProgress(listOfNotNull(local?.record), resource, version)
        val prefix = resourcePrefix(resource)
        val entries = try {
            backend.list(DIRECTORY)
        } catch (error: StorageFailure) {
            if (error.reason != StorageFailure.Reason.NOT_FOUND) throw error
            emptyList()
        }
        val records = mutableListOf<StorageProgressRecord>()
        local?.record?.let(records::add)
        for (entry in entries.filter { !it.directory && it.name.startsWith(prefix) && it.name.endsWith(".json") }) {
            if (entry.size !in 1..MAX_RECORD_SIZE) continue
            val bytes = backend.read(entry, 0, entry.size.toInt())
            val record =
                runCatching { store.json.decodeFromString<StorageProgressRecord>(bytes.decodeToString()) }.getOrNull()
                    ?: continue
            records += record
        }
        val winner = latestStorageProgress(records, resource, version)
        if (winner != null &&
            winner != local?.record
        ) {
            store.put("progress", resource, StoragePendingProgress(winner, false), winner.sequence)
        }
        winner
    }

    suspend fun flush() = mutex.withLock {
        if (!remoteEnabled) return@withLock
        val pending = store.list("progress").mapNotNull {
            store.json.decodeFromString<StoragePendingProgress>(it.payload).takeIf { item -> item.pending }
        }
        if (pending.isEmpty()) return@withLock
        ensureDirectory(DIRECTORY)
        for (item in pending) {
            val event = item.record
            val path = "$DIRECTORY/${resourcePrefix(event.resource)}${event.device}-${event.sequence}.json"
            val bytes = store.json.encodeToString(event).encodeToByteArray()
            require(bytes.size <= MAX_RECORD_SIZE)
            try {
                backend.write(path, bytes.inputStream(), bytes.size.toLong())
            } catch (error: StorageFailure) {
                if (error.reason != StorageFailure.Reason.CONFLICT) throw error
                val existing = backend.stat(path)
                if (existing.size != bytes.size.toLong() ||
                    !backend.read(existing, 0, bytes.size).contentEquals(bytes)
                ) {
                    throw error
                }
            }
            store.put("progress", event.resource, item.copy(pending = false), event.sequence)
            pruneOwnHistory(event, path)
        }
    }

    /** Keep other writers and content versions intact; only remove records dominated by a durable upload. */
    private suspend fun pruneOwnHistory(event: StorageProgressRecord, savedPath: String) {
        if (event.device != device) return
        val prefix = "${resourcePrefix(event.resource)}${event.device}-"
        for (entry in backend.list(DIRECTORY)) {
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
        private const val DIRECTORY = ".koharia/progress"
        private const val MAX_RECORD_SIZE = 64 * 1024L
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

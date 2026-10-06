package koharia.storage

import koharia.domain.storage.LibraryStorageRepository
import koharia.domain.storage.StorageRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable user state only; byte caches and this installation's writer identity are deliberately excluded. */
class StorageBackup(private val repository: LibraryStorageRepository, private val json: Json) {
    suspend fun create(connection: Long, account: String, root: String): String {
        val records = NAMESPACES.flatMap { namespace ->
            repository.list(connection, account, root, namespace).map {
                Entry(namespace, it.key, it.payload, it.revision)
            }
        }
        return json.encodeToString(Snapshot(account = account, root = root, entries = records))
    }

    suspend fun restore(connection: Long, expectedRoot: String, payload: String) {
        val snapshot = json.decodeFromString<Snapshot>(payload)
        require(snapshot.schema == 1 && snapshot.root == expectedRoot && snapshot.account.isNotBlank())
        require(snapshot.entries.all { it.namespace in NAMESPACES })
        for (entry in snapshot.entries) {
            val existing = repository.get(connection, snapshot.account, snapshot.root, entry.namespace, entry.key)
            if (entry.namespace == "progress") {
                val incoming = json.decodeFromString<StoragePendingProgress>(entry.payload)
                require(incoming.record.resource == entry.key && incoming.record.schema == 1)
                if (existing != null) {
                    val current = json.decodeFromString<StoragePendingProgress>(existing)
                    if (current.record.version != incoming.record.version) continue
                    val winner = latestStorageProgress(
                        listOf(current.record, incoming.record),
                        incoming.record.resource,
                        incoming.record.version,
                    )
                    if (winner != incoming.record) continue
                    if (current == incoming || (current.record == incoming.record && current.pending)) continue
                }
            }
            repository.put(
                connection,
                snapshot.account,
                snapshot.root,
                entry.namespace,
                StorageRecord(entry.key, entry.payload, entry.revision),
            )
        }
    }

    @Serializable private data class Snapshot(
        val schema: Int = 1,
        val account: String,
        val root: String,
        val entries: List<Entry>,
    )

    @Serializable private data class Entry(
        val namespace: String,
        val key: String,
        val payload: String,
        val revision: Long,
    )
    companion object {
        const val KEY = "network_storage_user_state"
        private val NAMESPACES = setOf("progress", "identities", "native-identities")
    }
}

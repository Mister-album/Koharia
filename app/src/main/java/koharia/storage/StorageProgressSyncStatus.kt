package koharia.storage

import koharia.connection.ConnectionProgressSyncStatus
import koharia.connection.ProgressStatuses
import koharia.domain.storage.StorageRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Pure local projection. It deliberately owns neither a backend nor a NetworkStorageRuntime. */
internal fun observeStorageProgressSyncStatus(
    store: StorageRecordStore,
    paths: Map<String, String>,
    remoteEnabled: Boolean,
    needsValidation: Boolean,
): Flow<ProgressStatuses> {
    val directoryKeys = paths.values.flatMap { listOf(it, StoragePath.parent(it)) }.distinct()
    return store.observeStatusRecords("directories", directoryKeys).map { rows ->
        val directories = store.decodeStatusRecords<StorageDirectorySnapshot>(rows)
        paths.mapValues { (_, path) ->
            val own = directories[path]
            val parent = directories[StoragePath.parent(path)]
            if (own?.valid == false || parent?.valid == false) {
                null
            } else {
                selectCachedStorageEntry(path, own?.value, parent?.value)
            }
        }
    }.distinctUntilChanged().flatMapLatest { entries ->
        combine(
            store.observeStatusRecords("identities", paths.values),
            store.observeStatusRecords("native-identities", entries.values.mapNotNull { it?.identity }),
        ) { pathRows, nativeRows ->
            val pathIdentities = store.decodeStatusRecords<String?>(pathRows)
            val nativeIdentities = store.decodeStatusRecords<String>(nativeRows)
            entries.mapValues { (_, entry) ->
                val pathIdentity = entry?.path?.let(pathIdentities::get)
                val nativeIdentity = entry?.identity?.let(nativeIdentities::get)
                if (entry == null || pathIdentity?.valid == false ||
                    (pathIdentity?.value == null && nativeIdentity?.valid == false)
                ) {
                    null
                } else {
                    StorageStatusTarget(
                        cachedStorageResourceIdentity(
                            store.session.rootIdentity,
                            entry,
                            pathIdentity?.value,
                            nativeIdentity?.value,
                        ),
                        entry.version,
                    )
                }
            }
        }.distinctUntilChanged()
    }.flatMapLatest { targets ->
        store.observeStatusRecords("progress", targets.values.mapNotNull { it?.resource }).map { rows ->
            val records = rows.associateBy { it.key }
            targets.mapValues { (_, target) ->
                if (target == null) {
                    setOf(ConnectionProgressSyncStatus.UNAVAILABLE)
                } else {
                    storageProgressStatus(store, records[target.resource], target, remoteEnabled, needsValidation)
                }
            }.filterValues { it.isNotEmpty() }
        }
    }
}

private data class StorageStatusTarget(val resource: String, val version: String)

private data class StorageStatusRecord<T>(val value: T?, val valid: Boolean)

private inline fun <reified T> StorageRecordStore.decodeStatusRecords(rows: List<StorageRecord>) = rows.associate {
    it.key to try {
        StorageStatusRecord(json.decodeFromString<T>(it.payload), true)
    } catch (_: IllegalArgumentException) {
        StorageStatusRecord<T>(null, false)
    }
}

private fun storageProgressStatus(
    store: StorageRecordStore,
    row: StorageRecord?,
    target: StorageStatusTarget,
    remoteEnabled: Boolean,
    needsValidation: Boolean,
): Set<ConnectionProgressSyncStatus> {
    if (row == null) return emptySet()
    return try {
        val state = decodeStoragePendingProgress(row, store.json)
        require(state.record.resource == target.resource)
        when {
            state.record.version != target.version -> setOf(ConnectionProgressSyncStatus.CONFLICT)
            state.requiresConfirmation -> setOf(ConnectionProgressSyncStatus.HELD)
            state.pending && (!remoteEnabled || needsValidation) -> setOf(ConnectionProgressSyncStatus.HELD)
            state.pending -> setOf(ConnectionProgressSyncStatus.PENDING)
            !remoteEnabled -> setOf(ConnectionProgressSyncStatus.LOCAL_ONLY)
            else -> emptySet()
        }
    } catch (_: IllegalArgumentException) {
        setOf(ConnectionProgressSyncStatus.UNAVAILABLE)
    }
}

private fun StorageRecordStore.observeStatusRecords(namespace: String, keys: Collection<String>) =
    repository.observe(session.connectionId, session.account, session.rootIdentity, namespace, keys).map {
        session.checkActive()
        it
    }

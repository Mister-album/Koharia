package koharia.storage

import koharia.domain.storage.LibraryStorageRepository
import koharia.domain.storage.StorageRecord
import koharia.domain.storage.StorageRecordUpdate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class StorageRecordStore(
    val session: StorageSession,
    val repository: LibraryStorageRepository,
    val json: Json,
) {
    suspend fun snapshot(namespace: String, key: String): StorageRecord? {
        session.checkActive()
        return repository.getSnapshot(session.connectionId, session.account, session.rootIdentity, namespace, key)
            .also { session.checkActive() }
    }

    suspend fun compareAndSet(updates: List<StorageRecordUpdate>, checkCurrent: () -> Unit = {}): Boolean {
        val checkOwner = {
            session.checkActive()
            checkCurrent()
        }
        checkOwner()
        return repository.compareAndSet(
            session.connectionId,
            session.account,
            session.rootIdentity,
            updates,
            checkOwner,
        )
            .also { checkOwner() }
    }

    suspend inline fun <reified T> get(namespace: String, key: String): T? {
        session.checkActive()
        val payload = repository.get(session.connectionId, session.account, session.rootIdentity, namespace, key)
        session.checkActive()
        return payload?.let { json.decodeFromString<T>(it) }
    }
    suspend inline fun <reified T> put(
        namespace: String,
        key: String,
        value: T,
        revision: Long = System.currentTimeMillis(),
    ) {
        session.checkActive()
        repository.put(
            session.connectionId,
            session.account,
            session.rootIdentity,
            namespace,
            StorageRecord(key, json.encodeToString(value), revision),
            session::checkActive,
        )
        session.checkActive()
    }
    suspend fun list(namespace: String): List<StorageRecord> {
        session.checkActive()
        return repository.list(session.connectionId, session.account, session.rootIdentity, namespace).also {
            session.checkActive()
        }
    }
    suspend fun matchesPayload(namespace: String, expected: StorageRecord): Boolean {
        session.checkActive()
        val current = repository.get(
            session.connectionId,
            session.account,
            session.rootIdentity,
            namespace,
            expected.key,
        )
        session.checkActive()
        return current == expected.payload
    }

    suspend inline fun <reified T> replaceIfUnchanged(namespace: String, expected: StorageRecord, updated: T): Boolean {
        session.checkActive()
        return repository.replaceIfUnchanged(
            session.connectionId,
            session.account,
            session.rootIdentity,
            namespace,
            expected,
            expected.copy(payload = json.encodeToString(updated)),
            session::checkActive,
        ).also { session.checkActive() }
    }

    suspend fun remove(namespace: String, key: String, revision: Long) {
        session.checkActive()
        repository.remove(
            session.connectionId,
            session.account,
            session.rootIdentity,
            namespace,
            key,
            revision,
            session::checkActive,
        )
    }
}

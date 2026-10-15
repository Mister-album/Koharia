package koharia.komga.progress

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.workDataOf
import koharia.domain.komga.KomgaReadStatusKey
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal interface KomgaProgressWorkBackend {
    suspend fun states(key: KomgaReadStatusKey): List<WorkInfo.State>
    suspend fun append(key: KomgaReadStatusKey)
}

/** Retain at most one queued successor while a worker runs; never replace an in-flight request. */
internal class KomgaProgressWakeups(private val backend: KomgaProgressWorkBackend) {
    private val gates = Array(64) { Mutex() }

    suspend fun wake(key: KomgaReadStatusKey): Boolean = gate(key).withLock {
        if (backend.states(key).any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }) {
            return@withLock false
        }
        backend.append(key)
        true
    }

    private fun gate(key: KomgaReadStatusKey) = gates[(key.hashCode() and Int.MAX_VALUE) % gates.size]
}

internal class KomgaProgressWorkManager(private val manager: WorkManager) : KomgaProgressWorkBackend {
    override suspend fun states(key: KomgaReadStatusKey) = manager.getWorkInfos(
        WorkQuery.Builder.fromTags(listOf(name(key))).addStates(
            listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING),
        ).build(),
    ).await().map { it.state }

    override suspend fun append(key: KomgaReadStatusKey) {
        val request = OneTimeWorkRequestBuilder<KomgaProgressQueueJob>()
            .setInputData(workDataOf("source" to key.connectionId, "owner" to key.ownerKey, "book" to key.bookId))
            .addTag(name(key))
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setInitialDelay(1, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniqueWork(name(key), ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.await()
    }

    companion object {
        fun name(key: KomgaReadStatusKey): String {
            val digest = MessageDigest.getInstance("SHA-256")
            listOf(key.connectionId.toString(), key.ownerKey, key.bookId).forEach {
                val bytes = it.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
            return "komga-outbox-" + digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

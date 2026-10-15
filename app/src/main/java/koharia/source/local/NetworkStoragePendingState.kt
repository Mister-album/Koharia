package koharia.source.local

import android.content.Context
import koharia.connection.ConnectionIdentityChanges
import koharia.domain.storage.LibraryStorageRepository
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStoragePreferences
import koharia.storage.NetworkStorageRuntime
import koharia.storage.StoragePendingSummary
import koharia.storage.StorageSession
import koharia.storage.storagePendingSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class NetworkStoragePendingState(
    val summary: StoragePendingSummary? = null,
    val writesAllowed: Boolean = false,
    val retry: (suspend () -> Unit)? = null,
    val owner: StorageSession? = null,
)

/** Settings observe local records only. A runtime may be acquired only by the captured retry action. */
internal fun observeNetworkStoragePendingState(context: Context, sourceId: Long): Flow<NetworkStoragePendingState?> {
    val preferences = NetworkStoragePreferences(sourceId)
    val identityChanges = Injekt.get<ConnectionIdentityChanges>()
    val repository = Injekt.get<LibraryStorageRepository>()
    val json = Injekt.get<Json>()
    return combine(preferences.identityChanges(), identityChanges.pausedIds) { _, paused -> sourceId in paused }
        .flatMapLatest { paused ->
            try {
                val config = preferences.configuration
                val account = preferences.account
                val username = preferences.username
                val password = preferences.password
                require(config.mode != LibraryStorageMode.LOCAL && config.rootIdentity.isNotBlank())
                fun current() = preferences.configuration == config && preferences.account == account &&
                    preferences.username == username && preferences.password == password
                val session = StorageSession(sourceId, account, config.rootIdentity, ::current)
                val writesAllowed = config.persistentIdentity && !config.needsValidation && !paused
                combine(
                    repository.observeNamespace(sourceId, account, config.rootIdentity, "progress"),
                    repository.observeNamespace(sourceId, account, config.rootIdentity, "mutations"),
                ) { progress, mutations ->
                    if (!current()) {
                        null
                    } else {
                        val summary = storagePendingSummary(progress, mutations.size, writesAllowed, json)
                        NetworkStoragePendingState(
                            summary,
                            writesAllowed,
                            retry = if (writesAllowed && (summary.pending > 0 || summary.mutations > 0)) {
                                {
                                    session.checkActive()
                                    check(!identityChanges.isPaused(sourceId))
                                    val runtime = NetworkStorageRuntime.getForOwner(
                                        context,
                                        sourceId,
                                        session::checkActive,
                                    )
                                    session.checkActive()
                                    check(runtime.account == account && runtime.config == config)
                                    runtime.mutations.reconcile()
                                    session.checkActive()
                                    runtime.progress.flush()
                                }
                            } else {
                                null
                            },
                            owner = session,
                        )
                    }
                }.onStart { emit(null) }.catch { emit(NetworkStoragePendingState()) }
            } catch (_: IllegalArgumentException) {
                flowOf(NetworkStoragePendingState())
            }
        }.flowOn(Dispatchers.IO)
}

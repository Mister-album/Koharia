package koharia.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.api.get

class ConnectionProfileManager(
    private val preferences: ConnectionPreferences,
    private val registry: ConnectionRegistry,
    private val configManager: ConnectionConfigManager,
    private val identityChanges: ConnectionIdentityChanges,
    private val flushSettings: (Long) -> Boolean = { id ->
        eu.kanade.tachiyomi.source.sourcePreferences("source_$id").edit().commit()
    },
) {
    private val replacementMutex = Mutex()
    private val edits = mutableMapOf<Long, LibraryConnectionProfile>()
    private val configurationCommit by lazy {
        ConnectionConfigurationCommit(
            identityChanges,
            { id -> AndroidSharedConfigStorage(eu.kanade.tachiyomi.source.sourcePreferences("source_$id")) },
            { id -> AndroidSharedConfigStorage(configurationDraft(id)) },
        )
    }

    private fun configurationDraft(id: Long) =
        eu.kanade.tachiyomi.source.sourcePreferences("connection_configuration_draft_$id")

    suspend fun commitConfiguration(id: Long, write: suspend (android.content.SharedPreferences) -> Unit) =
        commitConfiguration(id, null, write)

    internal suspend fun activateVerifiedIdentity(
        source: eu.kanade.tachiyomi.source.Source,
        writeAndQuarantine: suspend (android.content.SharedPreferences) -> Unit,
    ) = commitConfiguration(source.id, source, writeAndQuarantine)

    private suspend fun commitConfiguration(
        id: Long,
        activationSource: eu.kanade.tachiyomi.source.Source?,
        write: suspend (android.content.SharedPreferences) -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            replacementMutex.withLock {
                check(profiles().any { it.id == id }) { "Connection no longer exists" }
                check(!ConnectionRestoreState.isRestoring) { "Connections are being restored" }
                val manager = uy.kohesive.injekt.Injekt.get<tachiyomi.domain.source.service.SourceManager>()
                val restorer = checkNotNull(manager as? ConnectionSourceRestorer)
                if (activationSource != null) {
                    check(manager.get(id) === activationSource && identityChanges.isIdentityPaused(id))
                }
                configurationCommit.commit(
                    id,
                    replaceSource = { publish ->
                        restorer.withSourceConfiguration(id, publish)
                        check(manager.get(id) != null) { "Could not create updated connection source" }
                    },
                    writeDraft = { write(configurationDraft(id)) },
                )
                if (activationSource != null) {
                    check(!ConnectionRestoreState.isRestoring)
                    identityChanges.activateVerified(id)
                }
                (manager.get(id) as? ConnectionManagedLifecycle)?.onRegistered()
            }
        }

    /** Must run before SourceManager constructs any sources in a fresh process. */
    fun recoverConfigurationsBeforeSources() {
        identityChanges.pendingConfigurations().forEach { change ->
            runCatching {
                if (profiles().any { it.id == change.connectionId }) configurationCommit.recover(change.connectionId)
                configurationCommit.finishRecovery(change.connectionId)
            }.onFailure {
                logcat(logcat.LogPriority.ERROR, it) {
                    "Connection configuration remains paused"
                }
            }
        }
    }
    fun profiles(): List<LibraryConnectionProfile> = preferences.getProfiles()

    fun requiresIdentityReplacement(id: Long) = identityChanges.isIdentityPaused(id)

    @Synchronized
    fun add(providerId: String, name: String): LibraryConnectionProfile {
        requireNotNull(registry.provider(providerId)) { "Connection provider $providerId is unavailable" }
        val profile = LibraryConnectionProfile(
            id = preferences.allocateConnectionId(),
            providerId = providerId,
            name = uniqueName(providerId, name),
        )
        require(profile.name.isNotEmpty()) { "Connection name cannot be empty" }
        preferences.mutateProfiles { it + profile }
        return profile
    }

    @Synchronized
    fun update(profile: LibraryConnectionProfile) {
        check(profile.id !in edits) { "Connection is being edited" }
        if (profiles().firstOrNull { it.id == profile.id }?.name != profile.name) requireNameAvailable(profile)
        publishUpdate(profile)
    }

    private fun publishUpdate(profile: LibraryConnectionProfile) {
        preferences.mutateProfiles { current ->
            require(current.any { it.id == profile.id && it.providerId == profile.providerId }) {
                "Connection profile no longer exists"
            }
            current.map { if (it.id == profile.id) profile else it }
        }
    }

    /** Reserve a download name while its provider moves files, then publish only the matching profile. */
    suspend fun rename(
        connectionId: Long,
        requestedName: String,
        moveFiles: suspend (LibraryConnectionProfile, LibraryConnectionProfile) -> Unit,
    ) = withContext(Dispatchers.IO) {
        replacementMutex.withLock {
            val (before, after) = synchronized(this@ConnectionProfileManager) {
                val before = profiles().first { it.id == connectionId }
                val after = before.copy(name = requestedName.trim())
                requireNameAvailable(after)
                check(connectionId !in edits)
                identityChanges.reserveDirectories(listOf(before, after))
                edits[connectionId] = after
                before to after
            }
            try {
                moveFiles(before, after)
                synchronized(this@ConnectionProfileManager) {
                    check(profiles().firstOrNull { it.id == connectionId } == before) {
                        "Connection changed during rename"
                    }
                    publishUpdate(after)
                    preferences.flush()
                }
            } finally {
                synchronized(this@ConnectionProfileManager) { edits.remove(connectionId) }
            }
        }
    }

    private fun reservedProfiles() = profiles() + identityChanges.reservedProfiles() + edits.values

    private fun directoryKey(providerId: String, name: String) = providerId to
        (registry.provider(providerId)?.directoryNameFor(name) ?: name).lowercase(java.util.Locale.ROOT)

    private fun conflicts(profile: LibraryConnectionProfile) = reservedProfiles().any {
        it.id != profile.id && (
            it.name.equals(profile.name, ignoreCase = true) ||
                directoryKey(it.providerId, it.name) == directoryKey(profile.providerId, profile.name)
            )
    }

    private fun requireNameAvailable(profile: LibraryConnectionProfile) {
        require(profile.name.isNotBlank()) { "Connection name cannot be empty" }
        require(!conflicts(profile)) { "Connection name or directory is reserved" }
    }

    private fun uniqueName(providerId: String, name: String): String {
        val requested = name.trim()
        require(requested.isNotBlank()) { "Connection name cannot be empty" }
        val provider = registry.provider(providerId)
        var result = requested
        var suffix = 2
        while (conflicts(LibraryConnectionProfile(-1, providerId, result)) ||
            provider?.isConnectionNameAvailable(result) == false
        ) {
            result = "$requested (${suffix++})"
        }
        return result
    }

    /** The writer receives a fresh, unpublished ID and must not copy the old catalogue or reading state. */
    suspend fun replaceIdentity(
        previousId: Long,
        name: String,
        writeSettings: suspend (Long) -> Unit,
    ): LibraryConnectionProfile = withContext(Dispatchers.IO) {
        replacementMutex.withLock {
            val replacement = synchronized(this@ConnectionProfileManager) {
                val previous = profiles().first { it.id == previousId }
                requireNotNull(registry.provider(previous.providerId))
                val uniqueName = uniqueName(previous.providerId, name)
                var id = preferences.allocateConnectionId()
                while (identityChanges.isReserved(id)) id = preferences.allocateConnectionId()
                val replacement = previous.copy(id = id, name = uniqueName)
                identityChanges.prepare(previousId, replacement)
                replacement
            }
            writeSettings(replacement.id)
            check(flushSettings(replacement.id)) {
                "Could not persist connection settings"
            }
            withContext(NonCancellable) {
                identityChanges.ready(replacement.id)
                recoverIdentityChanges()
            }
            replacement
        }
    }

    @Synchronized
    fun recoverIdentityChanges() {
        identityChanges.pending().forEach { change ->
            val current = profiles()
            val existing = current.firstOrNull { it.id == change.profile.id }
            check(existing == null || existing == change.profile) { "Staged connection ID is already in use" }
            if (existing == null) preferences.mutateProfiles { it + change.profile }
            if (preferences.activeConnectionId.get() == change.previousId) {
                preferences.activeConnectionId.set(change.profile.id)
            }
            preferences.flush()
            identityChanges.published(change.profile.id)
        }
    }

    /** All imported owners stay unpublished until the complete import configuration has been flushed. */
    suspend fun importConnections(
        imported: List<LibraryConnectionProfile>,
        recoveryKey: String? = null,
        writeImport: suspend (Map<Long, LibraryConnectionProfile>) -> Unit,
    ): Map<Long, LibraryConnectionProfile> = withContext(Dispatchers.IO) {
        replacementMutex.withLock {
            val targets = synchronized(this@ConnectionProfileManager) {
                val importedIds = imported.mapTo(mutableSetOf()) { it.id }
                require(importedIds.size == imported.size)
                val reservedProfiles = reservedProfiles()
                val names = reservedProfiles.mapTo(mutableSetOf()) { it.name }
                fun directoryKey(profile: LibraryConnectionProfile, name: String): Pair<String, String> =
                    profile.providerId to (registry.provider(profile.providerId)?.directoryNameFor(name) ?: name)
                        .lowercase(java.util.Locale.ROOT)
                val directories = reservedProfiles.mapTo(mutableSetOf()) { directoryKey(it, it.name) }
                val interrupted = recoveryKey?.let(identityChanges::interruptedImport)
                if (interrupted != null) {
                    check(
                        interrupted.keys == importedIds && imported.all {
                            interrupted.getValue(it.id).providerId == it.providerId
                        },
                    ) { "Interrupted import ownership does not match backup" }
                    check(profiles().none { profile -> interrupted.values.any { it.id == profile.id } }) {
                        "Interrupted import was published before completion"
                    }
                }
                val targets = interrupted ?: imported.associate { old ->
                    require(old.id > 0 && old.name.isNotBlank() && old.providerId.isNotBlank())
                    val provider = registry.provider(old.providerId)
                    var name = old.name
                    var suffix = 2
                    while (name in names || directoryKey(old, name) in directories ||
                        provider?.isConnectionNameAvailable(name) == false
                    ) {
                        name = "${old.name} (${suffix++})"
                    }
                    names += name
                    directories += directoryKey(old, name)
                    var id = preferences.allocateConnectionId()
                    while (identityChanges.isReserved(id) || id in importedIds) {
                        id = preferences.allocateConnectionId()
                    }
                    old.id to old.copy(id = id, name = name)
                }
                if (interrupted == null) {
                    identityChanges.prepareImport(
                        targets.values.toList(),
                        recoveryKey,
                        targets.entries.associate { (old, target) -> target.id to old },
                    )
                }
                targets
            }
            writeImport(targets)
            targets.values.forEach { check(flushSettings(it.id)) { "Could not persist imported connection" } }
            withContext(NonCancellable) {
                identityChanges.readyImported(targets.values.mapTo(mutableSetOf()) { it.id })
                recoverIdentityChanges()
            }
            targets
        }
    }

    suspend fun remove(connectionId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        replacementMutex.withLock {
            val profile = synchronized(this@ConnectionProfileManager) {
                val profile = profiles().firstOrNull { it.id == connectionId }
                    ?: return@withLock Result.success(Unit)
                identityChanges.reserveDirectories(listOf(profile))
                edits[connectionId] = profile
                profile
            }
            try {
                registry.provider(profile.providerId)
                    ?.removeConnection(profile)
                    ?.mapCatching { handled ->
                        if (!handled) removeGenericProfile(profile)
                    }
                    ?: runCatching { removeGenericProfile(profile) }
            } finally {
                synchronized(this@ConnectionProfileManager) { edits.remove(connectionId) }
            }
        }
    }

    private fun removeGenericProfile(profile: LibraryConnectionProfile) {
        eu.kanade.tachiyomi.source.sourcePreferences("source_${profile.id}")
            .edit()
            .clear()
            .apply()
        configManager.clearScopeForConnection(profile.id)
        preferences.mutateProfiles { current -> current.filterNot { it.id == profile.id } }
        preferences.flush()
    }
}

package koharia.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A credential-free journal. Prepared settings are never visible until their writes are durable. */
class ConnectionIdentityChanges(
    private val storage: SharedConfigStorage,
    private val json: Json,
) {
    @Serializable
    data class Replacement(
        val previousId: Long?,
        val profile: LibraryConnectionProfile,
        val ready: Boolean = false,
        val published: Boolean = false,
        val pauseImportedWrites: Boolean = false,
        val importKey: String? = null,
        val importedFromId: Long? = null,
        val previousReactivated: Boolean = false,
    )

    private val records = MutableStateFlow(read())

    @Serializable
    data class Configuration(val connectionId: Long, val keys: Set<String>? = null)

    private var configurations: List<Configuration> = storage.values()[CONFIGURATION_KEY]?.let {
        json.decodeFromString<List<Configuration>>(checkNotNull(it as? String) { "Invalid configuration journal" })
    }.orEmpty()
    private var directoryReservations: List<LibraryConnectionProfile> = storage.values()[DIRECTORY_KEY]?.let {
        json.decodeFromString<List<LibraryConnectionProfile>>(
            checkNotNull(it as? String) { "Invalid connection directory journal" },
        )
    }.orEmpty()
    private val _pausedIds = MutableStateFlow(paused(records.value) + configurations.map { it.connectionId })
    val pausedIds = _pausedIds.asStateFlow()
    private val _identityPausedIds = MutableStateFlow(paused(records.value))
    val identityPausedIds = _identityPausedIds.asStateFlow()

    fun isPaused(id: Long) = id in _pausedIds.value
    fun isIdentityPaused(id: Long) = id in _identityPausedIds.value
    fun isReserved(id: Long) = records.value.any { it.profile.id == id }
    fun pending() = records.value.filter { it.ready && !it.published }

    @Synchronized
    fun pendingConfigurations() = configurations.toList()

    @Synchronized
    fun beginConfiguration(id: Long) {
        require(id > 0 && configurations.none { it.connectionId == id })
        writeConfigurations(configurations + Configuration(id))
    }

    @Synchronized
    fun readyConfiguration(id: Long, keys: Set<String>) {
        check(configurations.any { it.connectionId == id && it.keys == null })
        writeConfigurations(configurations.map { if (it.connectionId == id) it.copy(keys = keys) else it })
    }

    @Synchronized
    fun finishConfiguration(id: Long) {
        writeConfigurations(configurations.filterNot { it.connectionId == id })
    }

    private fun writeConfigurations(updated: List<Configuration>) {
        check(storage.commit(mapOf(CONFIGURATION_KEY to json.encodeToString(updated)))) {
            "Could not persist configuration change"
        }
        configurations = updated
        _pausedIds.value = paused(records.value) + updated.map { it.connectionId }
    }

    @Synchronized
    fun reservedProfiles() = records.value.map { it.profile } + directoryReservations

    @Synchronized
    fun reserveDirectories(profiles: List<LibraryConnectionProfile>) {
        require(profiles.all { it.id > 0 && it.providerId.isNotBlank() && it.name.isNotBlank() })
        val updated = (directoryReservations + profiles).distinct()
        check(storage.commit(mapOf(DIRECTORY_KEY to json.encodeToString(updated)))) {
            "Could not persist connection directory reservation"
        }
        directoryReservations = updated
    }

    fun interruptedImport(key: String): Map<Long, LibraryConnectionProfile>? {
        val interrupted = records.value.filter { it.importKey == key && !it.ready }
        if (interrupted.isEmpty()) return null
        check(interrupted.all { it.previousId == null && it.importedFromId != null && !it.published })
        return interrupted.associate { checkNotNull(it.importedFromId) to it.profile }
    }

    @Synchronized
    fun prepare(previousId: Long, profile: LibraryConnectionProfile) {
        require(previousId != profile.id && !isReserved(profile.id))
        write(records.value + Replacement(previousId, profile))
    }

    @Synchronized
    fun prepareImport(
        profiles: List<LibraryConnectionProfile>,
        importKey: String? = null,
        originalIds: Map<Long, Long> = emptyMap(),
    ) {
        require(profiles.map { it.id }.distinct().size == profiles.size)
        require(profiles.none { isReserved(it.id) })
        require(importKey == null || (importKey.isNotBlank() && profiles.all { it.id in originalIds }))
        write(
            records.value + profiles.map {
                Replacement(
                    null,
                    it,
                    pauseImportedWrites = true,
                    importKey = importKey,
                    importedFromId = originalIds[it.id],
                )
            },
        )
    }

    @Synchronized
    fun readyImported(ids: Set<Long>) {
        require(ids.all { id -> records.value.any { it.profile.id == id && it.previousId == null } })
        write(records.value.map { if (it.profile.id in ids) it.copy(ready = true) else it })
    }

    @Synchronized
    fun ready(id: Long) = update(id) { it.copy(ready = true) }

    @Synchronized
    fun published(id: Long) = update(id) {
        check(it.ready)
        it.copy(published = true)
    }

    /** Call only after verified configuration publication and durable quarantine of previous operations. */
    @Synchronized
    internal fun activateVerified(id: Long) {
        check(isIdentityPaused(id) && configurations.none { it.connectionId == id })
        val related = records.value.filter { it.previousId == id || it.profile.id == id }
        check(related.isNotEmpty() && related.all { it.ready && it.published })
        write(
            records.value.map {
                it.copy(
                    previousReactivated = it.previousReactivated || it.previousId == id,
                    pauseImportedWrites = it.pauseImportedWrites && it.profile.id != id,
                )
            },
        )
    }

    private fun update(id: Long, transform: (Replacement) -> Replacement) {
        check(isReserved(id))
        write(records.value.map { if (it.profile.id == id) transform(it) else it })
    }

    private fun read(): List<Replacement> {
        val value = storage.values()[KEY] ?: return emptyList()
        val encoded = checkNotNull(value as? String) { "Invalid connection change journal" }
        // A damaged ownership journal must not silently enable old writes.
        return json.decodeFromString(encoded)
    }

    private fun write(updated: List<Replacement>) {
        check(storage.commit(mapOf(KEY to json.encodeToString(updated)))) { "Could not persist connection change" }
        records.value = updated
        _identityPausedIds.value = paused(updated)
        _pausedIds.value = paused(updated) + configurations.map { it.connectionId }
    }

    private fun paused(values: List<Replacement>) = buildSet {
        values.filter { it.ready }.forEach { record ->
            if (!record.previousReactivated) record.previousId?.let(::add)
            if (record.pauseImportedWrites) add(record.profile.id)
        }
    }

    companion object {
        private const val KEY = "replacements"
        private const val DIRECTORY_KEY = "directory_reservations"
        private const val CONFIGURATION_KEY = "configurations"
    }
}

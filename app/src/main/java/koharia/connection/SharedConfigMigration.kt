package koharia.connection

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import tachiyomi.core.common.preference.Preference

/** All writes, including the completion marker, are committed in one preferences transaction. */
interface SharedConfigStorage {
    fun values(): Map<String, *>
    fun commit(values: Map<String, Any?>): Boolean
}

class AndroidSharedConfigStorage(private val preferences: SharedPreferences) : SharedConfigStorage {
    override fun values(): Map<String, *> = preferences.all

    override fun commit(values: Map<String, Any?>): Boolean {
        val previous = preferences.all
        if (write(values)) return true
        // SharedPreferences updates memory even if writing to disk fails.
        write(values.keys.associateWith { previous[it] })
        return false
    }

    private fun write(values: Map<String, Any?>): Boolean {
        val editor = preferences.edit()
        values.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported preference type")
            }
        }
        return editor.commit()
    }
}

class SharedConfigMigration(
    private val storage: SharedConfigStorage,
    defaults: () -> Map<String, Any>,
    private val profiles: () -> List<LibraryConnectionProfile>,
    private val json: Json,
) {
    constructor(
        storage: SharedConfigStorage,
        defaults: Map<String, Any>,
        profiles: () -> List<LibraryConnectionProfile>,
        json: Json,
    ) : this(storage, { defaults }, profiles, json)

    // Completed installations need only the migration marker during startup.
    private val defaults by lazy(defaults)
    private val _pending = MutableStateFlow(isPending())
    val pending = _pending.asStateFlow()

    fun captureUpgradeSelection() {
        val values = storage.values()
        if (values[COMPLETED] == true || values.containsKey(UPGRADE_SELECTION)) return
        if (legacySeparate(values)) {
            val id = normalizeLegacy(values)[ConnectionPreferences.PREF_ACTIVE_CONNECTION_ID] as? Long ?: -1L
            storage.commit(mapOf(UPGRADE_SELECTION to id))
        }
    }

    fun isPending(): Boolean {
        val values = storage.values()
        return values[RESTORE_PENDING] == true ||
            values[COMPLETED] != true
    }

    fun candidates(): List<LibraryConnectionProfile> {
        val values = storage.values()
        if (values[RESTORE_PENDING] != true) return profiles()
        val serialized = restoreValues(values)[ConnectionPreferences.PREF_PROFILES] as? Set<*>
        return serialized.orEmpty().mapNotNull {
            (it as? String)?.let { value ->
                runCatching {
                    val fields = json.parseToJsonElement(value).jsonObject
                    json.decodeFromString<LibraryConnectionProfile>(
                        JsonObject(mapOf("providerId" to JsonPrimitive("komga")) + fields).toString(),
                    )
                }.getOrNull()
            }
        }.distinctBy { it.id }.sortedBy { it.id }
    }

    fun defaultConnectionId(): Long? {
        val values = storage.values().let { if (it[RESTORE_PENDING] == true) restoreValues(it) else it }
        val id = (values[UPGRADE_SELECTION] ?: values[ConnectionPreferences.PREF_ACTIVE_CONNECTION_ID]) as? Long
        return id?.takeIf { candidate -> candidates().any { it.id == candidate } }
    }

    /** Authenticate against this device's effective settings, never a backup candidate. */
    fun requiresAuthentication(): Boolean {
        val values = normalizeLegacy(storage.values())
        val key = "use_biometric_lock"
        if (values[COMPLETED] != true && legacySeparate(values)) {
            val id = (values[UPGRADE_SELECTION] ?: values[ConnectionPreferences.PREF_ACTIVE_CONNECTION_ID]) as? Long
            if (id != null && id != NO_ACTIVE_CONNECTION) return values["connection_$id::$key"] == true
        }
        return (values[SHARED_PREFIX + key] ?: values[key]) == true
    }

    @Synchronized
    fun initialize(): Boolean {
        val values = storage.values()
        if (values[RESTORE_PENDING] == true || (values[COMPLETED] != true && legacySeparate(values))) {
            _pending.value = true
            return false
        }
        if (storage.values()[COMPLETED] == true) return true
        return select(null)
    }

    @Synchronized
    fun select(connectionId: Long?): Boolean {
        require(connectionId == null || candidates().any { it.id == connectionId })
        val all = storage.values()
        val restoring = all[RESTORE_PENDING] == true
        val source = normalizeLegacy(if (restoring) restoreValues(all) else all)
        val prefix = connectionId?.let { "connection_$it::" }
        val writes = mutableMapOf<String, Any?>()
        val keys = defaults.keys + all.keys.map { it.substringAfterLast("::") }.filter(::isDynamicConfigKey) +
            source.keys.map { it.substringAfterLast("::") }.filter(::isDynamicConfigKey)
        keys.forEach { key ->
            if (restoring && Preference.isAppState(key)) return@forEach
            val value = if (prefix != null) {
                source[prefix + key]
            } else {
                source[SHARED_PREFIX + key] ?: source[key]
            }
            writes[SHARED_PREFIX + key] = value
            // Missing independent values must use defaults, not legacy shared values.
            if (prefix != null || restoring) writes[key] = null
        }
        // Clock corrections belong to servers, not to the chosen reader configuration.
        source.forEach { (key, value) ->
            val match = SERVER_CLOCK_SCOPE.matchEntire(key) ?: return@forEach
            if (match.groupValues[1] == match.groupValues[2] && legacySeparate(source)) {
                writes[SHARED_PREFIX + key.substringAfterLast("::")] = value
            }
        }
        writes[COMPLETED] = true
        writes[UPGRADE_SELECTION] = null
        writes[ConnectionPreferences.PREF_CONFIG_MODE] = ConnectionConfigMode.Shared.name
        writes["komga_local_config_mode"] = null
        writes[RESTORE_PENDING] = null
        all.keys.filter { it.startsWith(RESTORE_PREFIX) }.forEach { writes[it] = null }
        if (!storage.commit(writes)) return false
        _pending.value = false
        return true
    }

    fun isConfigKey(key: String): Boolean = key.substringAfterLast("::").let {
        it in defaults || isDynamicConfigKey(it)
    }

    private fun isDynamicConfigKey(key: String): Boolean =
        Regex("pref_filter_library_tracked_\\d+_v2").matches(key) ||
            Regex(
                "__PRIVATE_(pref_mangasync_(username|password)|pref_tracker_auth_expired|track_token)_\\d+",
            ).matches(key)

    /** Restored candidates remain dormant until the entire restore finishes. */
    fun restoreKey(key: String, separate: Boolean): String? {
        val normalized = normalizeKey(key)
        if (Preference.isAppState(normalized.substringAfterLast("::"))) return null
        if (separate &&
            (isConfigKey(normalized) || normalized in RESTORE_METADATA || SERVER_CLOCK_SCOPE.matches(normalized))
        ) {
            return RESTORE_PREFIX + normalized
        }
        if (isRetiredKey(normalized)) return null
        return if (isConfigKey(normalized)) SHARED_PREFIX + normalized.substringAfterLast("::") else key
    }

    fun beginRestore(): Boolean = storage.commit(
        storage.values().keys.filter { it.startsWith(RESTORE_PREFIX) }.associateWith { null } +
            (RESTORE_PENDING to null),
    )

    fun finishRestore(separate: Boolean): Boolean {
        if (!separate) return true
        val success = storage.commit(mapOf(RESTORE_PENDING to true))
        if (success) _pending.value = true
        return success
    }

    private fun restoreValues(values: Map<String, *>): Map<String, *> = values
        .filterKeys { it.startsWith(RESTORE_PREFIX) }
        .mapKeys { it.key.removePrefix(RESTORE_PREFIX) }

    companion object {
        const val SHARED_PREFIX = "connection_shared::"
        val COMPLETED = Preference.appStateKey("shared_config_completed")
        val UPGRADE_SELECTION = Preference.appStateKey("shared_config_upgrade_selection")
        val RESTORE_PENDING = Preference.appStateKey("shared_config_restore_pending")
        val RESTORE_PREFIX = Preference.appStateKey("shared_config_restore::")
        private val RESTORE_METADATA = setOf(
            ConnectionPreferences.PREF_PROFILES,
            ConnectionPreferences.PREF_ACTIVE_CONNECTION_ID,
            ConnectionPreferences.PREF_CONFIG_MODE,
        )
        private val SERVER_CLOCK_SCOPE = Regex(
            "connection_(\\d+)::epub_reader_komga_server_timestamp_offset_minutes_(\\d+)",
        )

        fun legacySeparate(values: Map<String, *>): Boolean =
            (values[ConnectionPreferences.PREF_CONFIG_MODE] ?: values["komga_local_config_mode"]) ==
                ConnectionConfigMode.Separate.name

        fun isRetiredKey(key: String): Boolean = key == ConnectionPreferences.PREF_CONFIG_MODE ||
            key == "komga_local_config_mode" || key.startsWith("komga_local_shared::") ||
            key.startsWith("komga_local_server_") || Regex("connection_\\d+::.*").matches(key)

        fun normalizeKey(key: String): String = when {
            key.startsWith("komga_local_shared::") -> SHARED_PREFIX + key.removePrefix("komga_local_shared::")
            key.startsWith("komga_local_server_") -> "connection_" + key.removePrefix("komga_local_server_")
            key == "komga_server_profiles" -> ConnectionPreferences.PREF_PROFILES
            key == "komga_active_server_id" -> ConnectionPreferences.PREF_ACTIVE_CONNECTION_ID
            key == "komga_local_config_mode" -> ConnectionPreferences.PREF_CONFIG_MODE
            else -> key
        }

        fun normalizeLegacy(values: Map<String, *>): Map<String, *> =
            values.mapKeys { normalizeKey(it.key) } + values
    }
}

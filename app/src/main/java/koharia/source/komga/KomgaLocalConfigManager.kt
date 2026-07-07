package koharia.source.komga

import android.app.Application
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceScope
import tachiyomi.core.common.preference.PreferenceScopeProvider
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StoragePreferences

class KomgaLocalConfigManager(
    private val preferenceStore: PreferenceStore,
    private val serverPreferences: KomgaServerPreferences,
    private val scopedPreferenceKeys: Set<String>,
) : PreferenceScopeProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _canEditScopedPreferences = MutableStateFlow(canEditScopedPreferences())
    val canEditScopedPreferences: StateFlow<Boolean> = _canEditScopedPreferences.asStateFlow()

    init {
        scope.launch {
            scopeChanges()
                .map { resolvedScope ->
                    logcat(LogPriority.DEBUG) {
                        "Komga local config scope changed: " +
                            "localConfigMode=${serverPreferences.localConfigMode.get()}, " +
                            "activeServerId=${serverPreferences.activeServerId.get()}, " +
                            "scope=${resolvedScope.prefix}, " +
                            "allowLegacyFallback=${resolvedScope.allowLegacyFallback}"
                    }
                    canEditScopedPreferences()
                }
                .distinctUntilChanged()
                .collect { _canEditScopedPreferences.value = it }
        }
    }

    override fun currentScope(): PreferenceScope {
        return resolveScope(
            mode = serverPreferences.localConfigMode.get(),
            activeServerId = serverPreferences.activeServerId.get(),
        )
    }

    override fun scopeChanges(): Flow<PreferenceScope> {
        return combine(
            serverPreferences.localConfigMode.changes(),
            serverPreferences.activeServerId.changes(),
            ::resolveScope,
        ).distinctUntilChanged()
    }

    fun canEditScopedPreferences(): Boolean {
        return serverPreferences.localConfigMode.get() != LocalConfigMode.Separate ||
            serverPreferences.activeServerId.get() != KomgaServerPreferences.NO_ACTIVE_SERVER
    }

    fun setLocalConfigMode(mode: LocalConfigMode) {
        if (serverPreferences.localConfigMode.get() == mode) return

        logcat(LogPriority.DEBUG) {
            "Switching local config mode from ${serverPreferences.localConfigMode.get()} to $mode " +
                "(activeServerId=${serverPreferences.activeServerId.get()}, " +
                "profiles=${serverPreferences.getProfiles().map { it.id }})"
        }

        if (mode == LocalConfigMode.Separate) {
            cloneSharedScopeToProfiles(serverPreferences.getProfiles())
        }

        serverPreferences.localConfigMode.set(mode)
        _canEditScopedPreferences.value = canEditScopedPreferences()
    }

    fun initializeScopeForNewServer(serverId: Long) {
        if (serverPreferences.localConfigMode.get() != LocalConfigMode.Separate) return

        val targetPrefix = serverScopePrefix(serverId)
        if (preferenceStore.getAll().keys.any { it.startsWith(targetPrefix) }) {
            logcat(LogPriority.DEBUG) {
                "Skipping scoped config initialization for serverId=$serverId because scope already exists: $targetPrefix"
            }
            return
        }

        val source = captureCurrentEffectiveEntries()
        logcat(LogPriority.DEBUG) {
            "Initializing scoped config for new serverId=$serverId from current scope ${currentScope().prefix} " +
                "with ${source.size} entries"
        }
        copyEntries(
            source = source,
            targetPrefix = targetPrefix,
        )
    }

    private fun cloneSharedScopeToProfiles(profiles: List<KomgaServerProfile>) {
        if (profiles.isEmpty()) return

        val entries = captureSharedEntries()
        logcat(LogPriority.DEBUG) {
            "Cloning shared local config into ${profiles.size} server scopes with ${entries.size} entries"
        }
        profiles.forEach { profile ->
            copyEntries(entries, serverScopePrefix(profile.id))
        }
    }

    private fun captureCurrentEffectiveEntries(): Map<String, *> {
        val currentScope = currentScope()
        return if (currentScope.prefix == sharedScope.prefix) {
            captureSharedEntries()
        } else {
            capturePrefixedEntries(currentScope.prefix)
        }
    }

    private fun captureSharedEntries(): Map<String, *> {
        val allEntries = preferenceStore.getAll()
        return buildMap {
            scopedPreferenceKeys.forEach { key ->
                val sharedKey = sharedScope.prefix + key
                when {
                    allEntries.containsKey(sharedKey) -> put(key, allEntries.getValue(sharedKey))
                    allEntries.containsKey(key) -> put(key, allEntries.getValue(key))
                }
            }
        }
    }

    private fun capturePrefixedEntries(prefix: String): Map<String, *> {
        return preferenceStore.getAll()
            .filterKeys { it.startsWith(prefix) }
            .mapKeys { (key, _) -> key.removePrefix(prefix) }
    }

    private fun copyEntries(
        source: Map<String, *>,
        targetPrefix: String,
    ) {
        logcat(LogPriority.DEBUG) {
            "Copying ${source.size} scoped preference entries into scope $targetPrefix"
        }
        source.forEach { (key, value) ->
            setRawValue(targetPrefix + key, value)
        }
    }

    private fun setRawValue(
        key: String,
        value: Any?,
    ) {
        when (value) {
            is String -> preferenceStore.getString(key).set(value)
            is Int -> preferenceStore.getInt(key).set(value)
            is Long -> preferenceStore.getLong(key).set(value)
            is Float -> preferenceStore.getFloat(key).set(value)
            is Boolean -> preferenceStore.getBoolean(key).set(value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                (value as? Set<String>)?.let { preferenceStore.getStringSet(key).set(it) }
            }
        }
    }

    private fun resolveScope(
        mode: LocalConfigMode,
        activeServerId: Long,
    ): PreferenceScope {
        return when {
            mode == LocalConfigMode.Separate && activeServerId != KomgaServerPreferences.NO_ACTIVE_SERVER ->
                PreferenceScope(
                    prefix = serverScopePrefix(activeServerId),
                    allowLegacyFallback = false,
                )
            else -> sharedScope
        }
    }

    companion object {
        const val SHARED_SCOPE_NAME = "komga_local_shared"

        fun buildScopedPreferenceKeys(
            app: Application,
            verboseLoggingDefault: Boolean,
        ): Set<String> {
            val recorder = RecordingPreferenceStore()
            val folderProvider = object : FolderProvider {
                override fun directory() = app.cacheDir

                override fun path() = app.cacheDir.absolutePath
            }

            BasePreferences(app, recorder)
            SourcePreferences(recorder)
            LibraryPreferences(recorder)
            ReaderPreferences(recorder)
            DownloadPreferences(recorder)
            NetworkPreferences(recorder, verboseLoggingDefault)
            SecurityPreferences(recorder)
            PrivacyPreferences(recorder)
            TrackPreferences(recorder)
            BackupPreferences(recorder)
            StoragePreferences(folderProvider, recorder)
            UiPreferences(recorder)

            return recorder.recordedKeys
        }

        fun serverScopeName(serverId: Long): String = "komga_local_server_$serverId"

        private fun serverScopePrefix(serverId: Long): String = "${serverScopeName(serverId)}::"

        private val sharedScope = PreferenceScope(
            prefix = "$SHARED_SCOPE_NAME::",
            allowLegacyFallback = true,
        )
    }
}

private class RecordingPreferenceStore : PreferenceStore {
    private val _recordedKeys = linkedSetOf<String>()
    val recordedKeys: Set<String> = _recordedKeys

    override fun getString(key: String, defaultValue: String): Preference<String> = record(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> = record(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Preference<Int> = record(key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = record(key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = record(key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = record(
        key,
        defaultValue,
    )

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = record(key, defaultValue)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = record(key, defaultValue)

    override fun getAll(): Map<String, *> = emptyMap<String, Any>()

    private fun <T> record(
        key: String,
        defaultValue: T,
    ): Preference<T> {
        _recordedKeys += key
        return RecordingPreference(key, defaultValue)
    }
}

private class RecordingPreference<T>(
    private val key: String,
    private val defaultValue: T,
) : Preference<T> {
    override fun key(): String = key

    override fun get(): T = defaultValue

    override fun set(value: T) = Unit

    override fun isSet(): Boolean = false

    override fun delete() = Unit

    override fun defaultValue(): T = defaultValue

    override fun changes(): Flow<T> = kotlinx.coroutines.flow.flowOf(defaultValue)

    override fun stateIn(scope: CoroutineScope): StateFlow<T> {
        return MutableStateFlow(defaultValue).asStateFlow()
    }
}

package koharia.connection

import android.app.Application
import androidx.core.os.trace
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import koharia.epub.settings.EpubLayoutPreferences
import koharia.epub.settings.EpubReaderPreferences
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

class ConnectionConfigManager(
    private val preferenceStore: PreferenceStore,
) : PreferenceScopeProvider {

    override fun currentScope(): PreferenceScope = SharedAppPreferenceScope.currentScope()

    override fun scopeChanges(): Flow<PreferenceScope> = SharedAppPreferenceScope.scopeChanges()

    fun clearScopeForConnection(connectionId: Long) {
        val prefix = "${connectionScopeName(connectionId)}::"
        preferenceStore.getAll().keys.filter { it.startsWith(prefix) }
            .forEach { preferenceStore.getString(it).delete() }
    }

    companion object {
        const val SHARED_SCOPE_NAME = ConnectionPreferenceScopes.SHARED_SCOPE_NAME

        fun buildPreferenceDefaults(
            app: Application,
            verboseLoggingDefault: Boolean,
        ): Map<String, Any> = trace("Koharia.preferenceDefaults") {
            val recorder = RecordingPreferenceStore()
            val folderProvider = tachiyomi.core.common.storage.AndroidStorageFolderProvider(app)

            BasePreferences(app, recorder)
            SourcePreferences(recorder)
            LibraryPreferences(recorder)
            ReaderPreferences(recorder)
            EpubReaderPreferences(recorder)
            EpubLayoutPreferences(recorder)
            DownloadPreferences(recorder)
            NetworkPreferences(recorder, verboseLoggingDefault)
            SecurityPreferences(recorder)
            PrivacyPreferences(recorder)
            TrackPreferences(recorder)
            BackupPreferences(recorder)
            StoragePreferences(folderProvider, recorder)
            UiPreferences(recorder)

            recorder.defaults.filterKeys {
                !Preference.isAppState(it) || it == Preference.appStateKey("storage_dir")
            }
        }

        fun connectionScopeName(connectionId: Long): String =
            ConnectionPreferenceScopes.connectionScopeName(connectionId)
    }
}

private class RecordingPreferenceStore : PreferenceStore {
    val defaults = linkedMapOf<String, Any>()

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
    ): Preference<T> {
        defaults[key] = serializer(defaultValue)
        return RecordingPreference(key, defaultValue)
    }

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> {
        defaults[key] = serializer(defaultValue)
        return RecordingPreference(key, defaultValue)
    }

    override fun getAll(): Map<String, *> = emptyMap<String, Any>()

    private fun <T> record(
        key: String,
        defaultValue: T,
    ): Preference<T> {
        if (defaultValue != null) defaults[key] = defaultValue as Any
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

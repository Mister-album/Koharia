package koharia.connection

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import koharia.epub.font.EpubFontId
import koharia.epub.settings.EpubLayoutPreferences
import koharia.epub.settings.EpubReaderPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tachiyomi.core.common.preference.PreferenceScope
import tachiyomi.core.common.preference.PreferenceScopeProvider
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.ScopedPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

class ConnectionScopedPreferenceStoreFactory(
    private val app: Application,
    private val preferenceStore: PreferenceStore,
    private val connectionPreferences: ConnectionPreferences,
) {
    fun storeForConnection(connectionId: Long): ScopedPreferenceStore = ScopedPreferenceStore(
        preferenceStore = preferenceStore,
        scopeProvider = FixedConnectionScopeProvider(connectionPreferences, connectionId),
    )

    fun storeForServer(serverId: Long): ScopedPreferenceStore = storeForConnection(serverId)

    fun readerPreferences(connectionId: Long) = ReaderPreferences(storeForConnection(connectionId))
    fun epubReaderPreferences(connectionId: Long) = EpubReaderPreferences(storeForConnection(connectionId))
    fun epubLayoutPreferences(connectionId: Long) = EpubLayoutPreferences(storeForConnection(connectionId))
    fun basePreferences(connectionId: Long) = BasePreferences(app, storeForConnection(connectionId))
    fun downloadPreferences(connectionId: Long) = DownloadPreferences(storeForConnection(connectionId))
    fun trackPreferences(connectionId: Long) = TrackPreferences(storeForConnection(connectionId))
    fun libraryPreferences(connectionId: Long) = LibraryPreferences(storeForConnection(connectionId))

    fun resetEpubFontSelection(fontId: EpubFontId) {
        val key = EpubLayoutPreferences.SELECTED_FONT_KEY
        val scopedSuffix = "::$key"
        preferenceStore.getAll()
            .filter { (storedKey, value) ->
                (storedKey == key || storedKey.endsWith(scopedSuffix)) && value == fontId.value
            }
            .keys
            .forEach { storedKey ->
                preferenceStore.getString(storedKey, EpubFontId.ORIGINAL.value).set(EpubFontId.ORIGINAL.value)
            }
    }

    fun readerPreferencesForSavedSource(savedState: SavedStateHandle) =
        connectionIdFrom(savedState)?.let(::readerPreferences)

    fun storeForSavedSource(savedState: SavedStateHandle) = connectionIdFrom(savedState)?.let(::storeForConnection)

    fun epubReaderPreferencesForSavedSource(savedState: SavedStateHandle) =
        connectionIdFrom(savedState)?.let(::epubReaderPreferences)

    fun epubLayoutPreferencesForSavedSource(savedState: SavedStateHandle) =
        connectionIdFrom(savedState)?.let(::epubLayoutPreferences)

    fun basePreferencesForSavedSource(savedState: SavedStateHandle) =
        connectionIdFrom(savedState)?.let(::basePreferences)

    fun downloadPreferencesForSavedSource(savedState: SavedStateHandle) =
        connectionIdFrom(savedState)?.let(::downloadPreferences)

    fun trackPreferencesForSavedSource(savedState: SavedStateHandle) =
        connectionIdFrom(savedState)?.let(::trackPreferences)

    fun libraryPreferencesForSavedSource(savedState: SavedStateHandle) =
        connectionIdFrom(savedState)?.let(::libraryPreferences)

    private fun connectionIdFrom(savedState: SavedStateHandle): Long? {
        return (savedState.get<Long>("source_id") ?: savedState.get<Long>("source"))?.takeIf { it > 0L }
    }
}

object ConnectionPreferenceScopes {
    const val SHARED_SCOPE_NAME = "connection_shared"

    fun connectionScopeName(connectionId: Long): String = "connection_$connectionId"

    fun forConnection(mode: ConnectionConfigMode, connectionId: Long): PreferenceScope {
        return when {
            mode == ConnectionConfigMode.Separate && connectionId != NO_ACTIVE_CONNECTION -> PreferenceScope(
                prefix = "${connectionScopeName(connectionId)}::",
                allowLegacyFallback = false,
            )
            else -> PreferenceScope(
                prefix = "$SHARED_SCOPE_NAME::",
                allowLegacyFallback = true,
            )
        }
    }
}

private class FixedConnectionScopeProvider(
    private val preferences: ConnectionPreferences,
    private val connectionId: Long,
) : PreferenceScopeProvider {
    override fun currentScope(): PreferenceScope =
        ConnectionPreferenceScopes.forConnection(preferences.configMode.get(), connectionId)

    override fun scopeChanges(): Flow<PreferenceScope> = preferences.configMode.changes()
        .map { ConnectionPreferenceScopes.forConnection(it, connectionId) }
        .distinctUntilChanged()
}

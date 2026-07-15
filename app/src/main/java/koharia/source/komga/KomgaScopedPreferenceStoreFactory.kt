package koharia.source.komga

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
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

class KomgaScopedPreferenceStoreFactory(
    private val app: Application,
    private val preferenceStore: PreferenceStore,
    private val serverPreferences: KomgaServerPreferences,
) {

    fun storeForServer(serverId: Long): ScopedPreferenceStore {
        return ScopedPreferenceStore(
            preferenceStore = preferenceStore,
            scopeProvider = FixedKomgaScopeProvider(serverPreferences, serverId),
        )
    }

    fun readerPreferences(serverId: Long): ReaderPreferences {
        return ReaderPreferences(storeForServer(serverId))
    }

    fun epubReaderPreferences(serverId: Long): EpubReaderPreferences {
        return EpubReaderPreferences(storeForServer(serverId))
    }

    fun epubLayoutPreferences(serverId: Long): EpubLayoutPreferences {
        return EpubLayoutPreferences(storeForServer(serverId))
    }

    fun basePreferences(serverId: Long): BasePreferences {
        return BasePreferences(app, storeForServer(serverId))
    }

    fun downloadPreferences(serverId: Long): DownloadPreferences {
        return DownloadPreferences(storeForServer(serverId))
    }

    fun trackPreferences(serverId: Long): TrackPreferences {
        return TrackPreferences(storeForServer(serverId))
    }

    fun libraryPreferences(serverId: Long): LibraryPreferences {
        return LibraryPreferences(storeForServer(serverId))
    }

    fun readerPreferencesForSavedSource(savedState: SavedStateHandle): ReaderPreferences? {
        return sourceIdFrom(savedState)?.let(::readerPreferences)
    }

    fun epubReaderPreferencesForSavedSource(savedState: SavedStateHandle): EpubReaderPreferences? {
        return sourceIdFrom(savedState)?.let(::epubReaderPreferences)
    }

    fun epubLayoutPreferencesForSavedSource(savedState: SavedStateHandle): EpubLayoutPreferences? {
        return sourceIdFrom(savedState)?.let(::epubLayoutPreferences)
    }

    fun basePreferencesForSavedSource(savedState: SavedStateHandle): BasePreferences? {
        return sourceIdFrom(savedState)?.let(::basePreferences)
    }

    fun downloadPreferencesForSavedSource(savedState: SavedStateHandle): DownloadPreferences? {
        return sourceIdFrom(savedState)?.let(::downloadPreferences)
    }

    fun trackPreferencesForSavedSource(savedState: SavedStateHandle): TrackPreferences? {
        return sourceIdFrom(savedState)?.let(::trackPreferences)
    }

    fun libraryPreferencesForSavedSource(savedState: SavedStateHandle): LibraryPreferences? {
        return sourceIdFrom(savedState)?.let(::libraryPreferences)
    }

    private fun sourceIdFrom(savedState: SavedStateHandle): Long? {
        return (
            savedState.get<Long>("source_id")
                ?: savedState.get<Long>("source")
            )
            ?.takeIf { it > 0L }
    }
}

private class FixedKomgaScopeProvider(
    private val serverPreferences: KomgaServerPreferences,
    private val serverId: Long,
) : PreferenceScopeProvider {

    override fun currentScope(): PreferenceScope {
        return KomgaLocalConfigManager.scopeForServer(
            mode = serverPreferences.localConfigMode.get(),
            serverId = serverId,
        )
    }

    override fun scopeChanges(): Flow<PreferenceScope> {
        return serverPreferences.localConfigMode.changes()
            .map { mode ->
                KomgaLocalConfigManager.scopeForServer(
                    mode = mode,
                    serverId = serverId,
                )
            }
            .distinctUntilChanged()
    }
}

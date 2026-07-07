package eu.kanade.tachiyomi.di

import android.app.Application
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import koharia.source.komga.KomgaLocalConfigManager
import koharia.source.komga.KomgaServerPreferences
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.ScopedPreferenceStore
import tachiyomi.core.common.storage.AndroidStorageFolderProvider
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class PreferenceModule(val app: Application) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory<PreferenceStore> {
            AndroidPreferenceStore(app)
        }
        addSingletonFactory {
            KomgaLocalConfigManager(
                preferenceStore = get<PreferenceStore>(),
                serverPreferences = get<KomgaServerPreferences>(),
                scopedPreferenceKeys = KomgaLocalConfigManager.buildScopedPreferenceKeys(
                    app = app,
                    verboseLoggingDefault = isDebugBuildType,
                ),
            )
        }
        addSingletonFactory {
            ScopedPreferenceStore(
                preferenceStore = get<PreferenceStore>(),
                scopeProvider = get<KomgaLocalConfigManager>(),
            )
        }
        addSingletonFactory {
            NetworkPreferences(
                preferenceStore = get<ScopedPreferenceStore>(),
                verboseLoggingDefault = isDebugBuildType,
            )
        }
        addSingletonFactory {
            SourcePreferences(get<ScopedPreferenceStore>())
        }
        addSingletonFactory {
            SecurityPreferences(get<ScopedPreferenceStore>())
        }
        addSingletonFactory {
            PrivacyPreferences(get<ScopedPreferenceStore>())
        }
        addSingletonFactory {
            LibraryPreferences(get<ScopedPreferenceStore>())
        }
        addSingletonFactory {
            UpdatesPreferences(get())
        }
        addSingletonFactory {
            ReaderPreferences(get<ScopedPreferenceStore>())
        }
        addSingletonFactory {
            TrackPreferences(get<ScopedPreferenceStore>())
        }
        addSingletonFactory {
            DownloadPreferences(get<ScopedPreferenceStore>())
        }
        addSingletonFactory {
            BackupPreferences(get<ScopedPreferenceStore>())
        }
        addSingletonFactory {
            StoragePreferences(
                folderProvider = get<AndroidStorageFolderProvider>(),
                preferenceStore = get<ScopedPreferenceStore>(),
            )
        }
        addSingletonFactory {
            UiPreferences(get<ScopedPreferenceStore>())
        }
        addSingletonFactory {
            BasePreferences(app, get<ScopedPreferenceStore>())
        }
    }
}

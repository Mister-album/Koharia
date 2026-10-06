package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.preferenceKey
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PreferenceBackupCreator(
    private val sourceManager: SourceManager = Injekt.get(),
    private val preferenceStore: PreferenceStore = Injekt.get(),
    private val connectionPreferences: ConnectionPreferences = Injekt.get(),
) {

    fun createApp(includePrivatePreferences: Boolean): List<BackupPreference> {
        val all = preferenceStore.getAll()
        return all.toBackupPreferences()
            .filterNot { koharia.connection.SharedConfigMigration.isRetiredKey(it.key) }
            .filterNot { !it.key.contains("::") && "connection_shared::${it.key}" in all }
            .withPrivatePreferences(includePrivatePreferences)
    }

    suspend fun createSource(includePrivatePreferences: Boolean): List<BackupSourcePreferences> {
        val sourceKeys = sourceManager.getCatalogueSources()
            .filterIsInstance<ConfigurableSource>()
            .map { it.preferenceKey() }
            .plus(connectionPreferences.getProfiles().map { "source_${it.id}" })
            .distinct()
            .sorted()
        return sourceKeys.map { key ->
            val connectionId = key.removePrefix("source_").toLongOrNull()
            val network = connectionId?.let { koharia.storage.NetworkStoragePreferences(it) }
            val state = if (network != null && network.configuration.mode != koharia.storage.LibraryStorageMode.LOCAL) {
                val payload = koharia.storage.StorageBackup(Injekt.get(), Injekt.get()).create(
                    network.connectionId,
                    network.account,
                    network.configuration.rootIdentity,
                )
                listOf(BackupPreference(koharia.storage.StorageBackup.KEY, StringPreferenceValue(payload)))
            } else {
                emptyList()
            }
            BackupSourcePreferences(
                key,
                sourcePreferences(key).all.toBackupPreferences()
                    .withPrivatePreferences(includePrivatePreferences) + state,
            )
        }
            .filter { it.prefs.isNotEmpty() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, *>.toBackupPreferences(): List<BackupPreference> {
        return this
            .filterKeys { !Preference.isAppState(it.withoutScopePrefix()) }
            .mapNotNull { (key, value) ->
                when (value) {
                    is Int -> BackupPreference(key, IntPreferenceValue(value))
                    is Long -> BackupPreference(key, LongPreferenceValue(value))
                    is Float -> BackupPreference(key, FloatPreferenceValue(value))
                    is String -> BackupPreference(key, StringPreferenceValue(value))
                    is Boolean -> BackupPreference(key, BooleanPreferenceValue(value))
                    is Set<*> -> (value as? Set<String>)?.let {
                        BackupPreference(key, StringSetPreferenceValue(it))
                    }
                    else -> null
                }
            }
    }

    private fun List<BackupPreference>.withPrivatePreferences(include: Boolean) =
        if (include) {
            this
        } else {
            this.filter { !Preference.isPrivate(it.key.withoutScopePrefix()) }
        }
}

private fun String.withoutScopePrefix(): String {
    return substringAfterLast("::", this)
}

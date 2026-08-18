package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import android.util.Log
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionBackupRestorePolicy
import koharia.connection.ConnectionPreferences
import koharia.source.komga.KomgaConnectionMigration
import koharia.source.komga.KomgaSource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.plusAssign
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PreferenceRestorer(
    private val context: Context,
    private val getCategories: GetCategories = Injekt.get(),
    private val preferenceStore: PreferenceStore = Injekt.get(),
    private val komgaConnectionMigration: KomgaConnectionMigration = Injekt.get(),
    private val connectionPreferences: ConnectionPreferences = Injekt.get(),
) {
    private val connectionRestorePolicy = ConnectionBackupRestorePolicy(
        genericKeyPrefix = CONNECTION_KEY_PREFIX,
        legacyAppKeys = LEGACY_CONNECTION_KEYS,
        legacySourceKeys = setOf(LEGACY_KOMGA_SOURCE_KEY),
    )

    suspend fun restoreApp(
        preferences: List<BackupPreference>,
        backupCategories: List<BackupCategory>?,
    ) {
        restorePreferences(
            preferences,
            preferenceStore,
            backupCategories,
        )
        if (connectionRestorePolicy.recordAppRestore(preferences.map(BackupPreference::key))) {
            komgaConnectionMigration.migrate(forceLegacyInventory = true)
        } else {
            komgaConnectionMigration.migrate()
        }

        LibraryUpdateJob.setupTask(context)
        BackupCreateJob.setupTask(context)
    }

    suspend fun restoreSource(preferences: List<BackupSourcePreferences>) {
        preferences.forEach {
            if (!it.sourceKey.startsWith("source_")) return@forEach
            val sourcePrefs = AndroidPreferenceStore(context, sourcePreferences(it.sourceKey))
            restorePreferences(it.prefs, sourcePrefs)
        }
        if (connectionRestorePolicy.shouldForceLegacyInventoryAfterSourceRestore(
                sourceKeys = preferences.map(BackupSourcePreferences::sourceKey),
                hasConnectionProfiles = connectionPreferences.getProfiles().isNotEmpty(),
            )
        ) {
            komgaConnectionMigration.migrate(forceLegacyInventory = true)
        }
    }

    private suspend fun restorePreferences(
        toRestore: List<BackupPreference>,
        preferenceStore: PreferenceStore,
        backupCategories: List<BackupCategory>? = null,
    ) {
        val allCategories = if (backupCategories != null) getCategories.await() else emptyList()
        val categoriesByName = allCategories.associateBy { it.name }
        val backupCategoriesById = backupCategories?.associateBy { it.id.toString() }.orEmpty()
        val prefs = preferenceStore.getAll()
        toRestore.forEach { (key, value) ->
            try {
                val normalizedKey = key.withoutScopePrefix()
                val existingValue = prefs[key]
                when (value) {
                    is IntPreferenceValue -> {
                        if (existingValue == null || existingValue is Int) {
                            val newValue = if (normalizedKey == LibraryPreferences.DEFAULT_CATEGORY_PREF_KEY) {
                                backupCategoriesById[value.value.toString()]?.let {
                                    categoriesByName[it.name]?.id?.toInt()
                                }
                            } else {
                                value.value
                            }

                            newValue?.let { preferenceStore.getInt(key).set(it) }
                        }
                    }
                    is LongPreferenceValue -> {
                        if (existingValue == null || existingValue is Long) {
                            preferenceStore.getLong(key).set(value.value)
                        }
                    }
                    is FloatPreferenceValue -> {
                        if (existingValue == null || existingValue is Float) {
                            preferenceStore.getFloat(key).set(value.value)
                        }
                    }
                    is StringPreferenceValue -> {
                        if (existingValue == null || existingValue is String) {
                            preferenceStore.getString(key).set(value.value)
                        }
                    }
                    is BooleanPreferenceValue -> {
                        if (existingValue == null || existingValue is Boolean) {
                            preferenceStore.getBoolean(key).set(value.value)
                        }
                    }
                    is StringSetPreferenceValue -> {
                        if (existingValue == null || existingValue is Set<*>?) {
                            val restored = restoreCategoriesPreference(
                                normalizedKey,
                                value.value,
                                preferenceStore,
                                key,
                                backupCategoriesById,
                                categoriesByName,
                            )
                            if (!restored) preferenceStore.getStringSet(key).set(value.value)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("PreferenceRestorer", "Failed to restore preference <$key>", e)
            }
        }
    }

    private fun restoreCategoriesPreference(
        normalizedKey: String,
        value: Set<String>,
        preferenceStore: PreferenceStore,
        targetKey: String,
        backupCategoriesById: Map<String, BackupCategory>,
        categoriesByName: Map<String, Category>,
    ): Boolean {
        val categoryPreferences = LibraryPreferences.categoryPreferenceKeys + DownloadPreferences.categoryPreferenceKeys
        if (normalizedKey !in categoryPreferences) return false

        val ids = value.mapNotNull {
            backupCategoriesById[it]?.name?.let { name ->
                categoriesByName[name]?.id?.toString()
            }
        }

        if (ids.isNotEmpty()) {
            preferenceStore.getStringSet(targetKey) += ids
        }
        return true
    }
}

private const val CONNECTION_KEY_PREFIX = "connection_"
private val LEGACY_KOMGA_SOURCE_KEY = "source_${KomgaSource.ID}"
private val LEGACY_CONNECTION_KEYS = setOf(
    "komga_server_profiles",
    "komga_active_server_id",
    "komga_local_config_mode",
    "komga_has_initialized_profiles",
)

private fun String.withoutScopePrefix(): String {
    return substringAfterLast("::", this)
}

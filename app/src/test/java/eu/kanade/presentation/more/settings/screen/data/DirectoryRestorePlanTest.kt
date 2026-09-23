package eu.kanade.presentation.more.settings.screen.data

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryRootConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DirectoryRestorePlanTest {
    @Test
    fun `collects global and local directories with correct permissions`() {
        val shared = "content://trees/shared"
        val readOnly = "content://trees/read-only"
        val anotherLibrary = "content://trees/another-library"
        val config = LocalLibraryConfig(
            roots = listOf(
                LocalLibraryRootConfig(treeUri = shared, managed = true),
                LocalLibraryRootConfig(treeUri = readOnly),
            ),
        )
        val backup = Backup(
            backupManga = emptyList(),
            backupStorageDirectory = shared,
            backupSourcePreferences = listOf(
                BackupSourcePreferences(
                    "source_42",
                    listOf(
                        BackupPreference("local_library_config", StringPreferenceValue(Json.encodeToString(config))),
                    ),
                ),
                BackupSourcePreferences(
                    "source_43",
                    listOf(
                        BackupPreference(
                            "local_library_config",
                            StringPreferenceValue(
                                Json.encodeToString(
                                    LocalLibraryConfig(
                                        roots = listOf(LocalLibraryRootConfig(treeUri = anotherLibrary)),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val requirements = DirectoryRestorePlan.requirements(backup).associateBy { it.originalUri }
        assertEquals(3, requirements.size)
        assertTrue(requirements.getValue(shared).requiresWrite(RestoreOptions()))
        assertTrue(requirements.getValue(shared).isRequired(RestoreOptions(appSettings = false)))
        assertFalse(requirements.getValue(readOnly).requiresWrite(RestoreOptions()))
        assertFalse(requirements.getValue(readOnly).isRequired(RestoreOptions(connectionSettings = false)))
        assertTrue(requirements.getValue(anotherLibrary).isRequired(RestoreOptions(appSettings = false)))
    }
}

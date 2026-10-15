package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupConnection
import eu.kanade.tachiyomi.data.backup.restore.BackupConnectionInventory
import io.mockk.every
import io.mockk.mockk
import koharia.connection.ConnectionIdentityChanges
import koharia.connection.ConnectionPreferences
import koharia.connection.LibraryConnectionProfile
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.source.service.SourceManager

class BackupConnectionCreatorTest {
    @Test
    fun `private switch also protects legacy credentials inside shared scopes without renaming keys`() {
        val privateKeys = listOf(
            "Username",
            "Password",
            "API key",
            "Api key",
            "connection_shared::Password",
            "connection_shared::__PRIVATE_token",
        )
        val all = (privateKeys + "ordinary_setting").associateWith { "fixture-canary" }
        val store = mockk<PreferenceStore> { every { getAll() } returns all }
        val creator = PreferenceBackupCreator(mockk(), store, mockk(), mockk())
        assertEquals(listOf("ordinary_setting"), creator.createApp(false).map { it.key })
        // The existing shared-setting migration intentionally suppresses the shadowed global copy.
        assertEquals(all.keys - "Password", creator.createApp(true).map { it.key }.toSet())
    }

    @Test
    fun `removed owners retain immutable provider mapping and unavailable source names in backups`() {
        val preferences = ConnectionPreferences(InMemoryPreferenceStore(), Json)
        val removed = LibraryConnectionProfile(17, "lanraragi", "Removed")
        val live = LibraryConnectionProfile(18, "komga", "Live")
        preferences.setProfiles(listOf(removed, live))
        preferences.setProfiles(listOf(live))
        val sources = mockk<SourceManager> {
            every { getOrStub(17) } returns mockk { every { name } returns "Removed archive library" }
        }
        val changes = mockk<ConnectionIdentityChanges> { every { isPaused(18) } returns false }
        val creator = PreferenceBackupCreator(sources, InMemoryPreferenceStore(), preferences, changes)
        val historical = BackupConnection(17, "lanraragi", "Removed archive library", true)
        assertEquals(listOf(historical), creator.createConnections(setOf(17), includeAll = false))
        val inventory = creator.createConnections(setOf(17), includeAll = true)
        assertEquals(listOf(historical, BackupConnection(18, "komga", "Live")), inventory)
        assertEquals(inventory, BackupConnectionInventory.read(Backup(emptyList(), backupConnections = inventory)))
    }
}

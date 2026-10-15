package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class BackupRestoreKeyTest {
    @Test
    fun `recovery distinguishes payload selection and directory bindings but ignores map insertion order`() {
        val backup = Backup(emptyList(), backupConnections = listOf(BackupConnection(7, "komga", "Home")))
        val options = RestoreOptions()
        val bindings = mapOf("one" to "root-one", "two" to "root-two")
        val key = BackupRestoreKey.create(backup, options, bindings)
        assertEquals(
            key,
            BackupRestoreKey.create(
                backup,
                options,
                bindings.entries.reversed().associate {
                    it.toPair()
                },
            ),
        )
        assertNotEquals(key, BackupRestoreKey.create(backup.copy(backupConnections = emptyList()), options, bindings))
        assertNotEquals(key, BackupRestoreKey.create(backup, options.copy(appSettings = false), bindings))
        assertNotEquals(key, BackupRestoreKey.create(backup, options, bindings + ("one" to "different-root")))
    }
}

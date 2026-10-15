package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupConnection
import eu.kanade.tachiyomi.data.backup.models.BackupKomgaReadStatus
import koharia.connection.LibraryConnectionProfile
import koharia.domain.komga.KomgaReadStatusIntent
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupKomgaReadStatusIntentsTest {
    private val owner = BackupConnection(17, "komga", "Home")
    private val pending = KomgaReadStatusIntent(
        KomgaReadStatusKey(17, "credential-derived-fingerprint", "book"),
        2,
        false,
        100,
        "old-server-baseline",
        KomgaReadStatusPhase.PENDING,
    )

    @Test
    fun `export retains distinct owners without credentials baselines or settled rows`() {
        val second = pending.copy(key = pending.key.copy(ownerKey = "other-owner"), read = true)
        val settled = pending.copy(key = pending.key.copy(bookId = "settled"), phase = KomgaReadStatusPhase.SYNCED)
        val exported = BackupKomgaReadStatusIntents.export(listOf(second, settled, pending))
        assertEquals(2, exported.size)
        assertEquals(2, exported.map { it.ownerSlot }.distinct().size)
        assertEquals(exported, BackupKomgaReadStatusIntents.export(listOf(pending, settled, second)))
        val backup = Backup(emptyList(), backupConnections = listOf(owner), backupKomgaReadStatus = exported)
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)
        assertFalse(bytes.toString(Charsets.UTF_8).contains(pending.key.ownerKey))
        assertFalse(bytes.toString(Charsets.UTF_8).contains(checkNotNull(pending.baseline)))
        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
        assertEquals(backup, decoded)
        assertEquals(listOf(owner), BackupConnectionInventory.read(decoded))
    }

    @Test
    fun `missing wrong and duplicate ownership fails preflight`() {
        val entries = BackupKomgaReadStatusIntents.export(listOf(pending))
        for (owners in listOf(emptyList(), listOf(owner.copy(providerId = "kavita")))) {
            assertThrows(IllegalArgumentException::class.java) {
                BackupConnectionInventory.read(
                    Backup(emptyList(), backupConnections = owners, backupKomgaReadStatus = entries),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupKomgaReadStatusIntents.validate(entries + entries, listOf(owner))
        }
    }

    @Test
    fun `invalid payloads fail before any profile changes`() {
        val item = BackupKomgaReadStatus(17, 0, "book", 1, false, 0)
        for (invalid in listOf(
            item.copy(ownerSlot = -1),
            item.copy(revision = 0),
            item.copy(requestedAt = -1),
            item.copy(bookId = ""),
            item.copy(bookId = "x\n"),
            item.copy(bookId = "x".repeat(257)),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                BackupKomgaReadStatusIntents.validate(listOf(invalid), listOf(owner))
            }
        }
    }

    @Test
    fun `restored intents always use remapped held namespace and stable retry keys`() {
        val rows = BackupKomgaReadStatusIntents.export(listOf(pending))
        val target = LibraryConnectionProfile(28, "komga", "Imported")
        val targets = mapOf(17L to target)
        val key = "a".repeat(64)
        val restored = BackupKomgaReadStatusIntents.remap(rows, targets, key)
        val intent = restored.single()
        assertEquals(28L, intent.key.connectionId)
        assertEquals(KomgaReadStatusPhase.HELD, intent.phase)
        assertEquals(null, intent.baseline)
        assertTrue(intent.key.ownerKey.startsWith("restored:"))
        assertNotEquals(pending.key.ownerKey, intent.key.ownerKey)
        assertEquals(restored, BackupKomgaReadStatusIntents.remap(rows, targets, key))
        assertThrows(IllegalArgumentException::class.java) {
            BackupKomgaReadStatusIntents.remap(rows, mapOf(17L to target.copy(id = 17)), key)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupKomgaReadStatusIntents.remap(rows, mapOf(17L to target.copy(providerId = "kavita")), key)
        }
    }
}

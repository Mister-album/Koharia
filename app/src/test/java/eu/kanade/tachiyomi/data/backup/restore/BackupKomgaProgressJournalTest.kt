package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupConnection
import eu.kanade.tachiyomi.data.backup.models.BackupKomgaPageEvent
import koharia.connection.LibraryConnectionProfile
import koharia.domain.komga.KomgaPageAttempt
import koharia.domain.komga.KomgaPageEvent
import koharia.domain.komga.KomgaPageState
import koharia.domain.komga.KomgaReadStatusAttempt
import koharia.domain.komga.KomgaReadStatusIntent
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupKomgaProgressJournalTest {
    private val owner = BackupConnection(17, "komga", "Home")
    private val page = KomgaPageEvent(
        KomgaReadStatusKey(17, "account-a", "book"),
        3,
        0,
        4,
        10,
        100,
        "server-baseline",
        KomgaReadStatusPhase.PENDING,
    )
    private val intent = KomgaReadStatusIntent(
        page.key.copy(ownerKey = "account-z"),
        2,
        false,
        101,
        "server-baseline",
        KomgaReadStatusPhase.PENDING,
    )

    @Test
    fun `all tables share account slots and uncertain old requests survive newer desired state`() {
        val old = page.copy(generation = 2, page = 2)
        val request = KomgaReadStatusAttempt(intent.copy(revision = 1, read = true), "request-baseline")
        val extra = KomgaReadStatusAttempt(intent.copy(key = page.key.copy(ownerKey = "account-m")), "secret-marker")
        val exported = BackupKomgaProgressJournal.export(
            listOf(intent),
            listOf(KomgaPageState(page, KomgaPageAttempt(old, "request-baseline"))),
            listOf(extra, request),
        )
        assertEquals(0, exported.pages.single().ownerSlot)
        assertEquals(0, exported.pageAttempts.single().ownerSlot)
        assertEquals(2, exported.intents.single().ownerSlot)
        assertEquals(listOf(1, 2), exported.readAttempts.map { it.ownerSlot })
        val backup = Backup(
            emptyList(),
            backupConnections = listOf(owner),
            backupKomgaReadStatus = exported.intents,
            backupKomgaPages = exported.pages,
            backupKomgaPageAttempts = exported.pageAttempts,
            backupKomgaReadStatusAttempts = exported.readAttempts,
        )
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)
        for (secret in listOf(
            "account-a",
            "account-m",
            "account-z",
            "server-baseline",
            "request-baseline",
            "secret-marker",
        )) {
            assertFalse(bytes.toString(Charsets.UTF_8).contains(secret))
        }
        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
        assertEquals(backup, decoded)
        assertEquals(listOf(owner), BackupConnectionInventory.read(decoded))
        val restored = BackupKomgaProgressJournal.remap(
            decoded,
            mapOf(17L to LibraryConnectionProfile(28, "komga", "New")),
            "a".repeat(64),
        )
        val state = restored.pages.single()
        val event = checkNotNull(state.event)
        val attempt = checkNotNull(state.attempt)
        assertEquals(3L, event.generation)
        assertEquals(KomgaReadStatusPhase.HELD, event.phase)
        assertEquals(2L, attempt.event.generation)
        assertEquals("restored:unknown", attempt.baseline)
        assertNull(event.baseline)
        assertTrue(
            restored.readAttempts.all {
                it.intent.key.connectionId == 28L &&
                    it.intent.key.ownerKey.startsWith("restored:")
            },
        )
        assertEquals(listOf(2L, 1L), restored.readAttempts.map { it.intent.revision })
    }

    @Test
    fun `invalid page payloads and ownership fail before restore`() {
        val valid = BackupKomgaPageEvent(17, 0, "book", 1, 0, 2, 10, 100)
        for (invalid in listOf(
            valid.copy(generation = 0),
            valid.copy(revision = -1),
            valid.copy(page = 10),
            valid.copy(total = 0),
            valid.copy(ownerSlot = -1),
            valid.copy(bookId = "bad\n"),
            valid.copy(readAt = -1),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                BackupConnectionInventory.read(
                    Backup(emptyList(), backupConnections = listOf(owner), backupKomgaPages = listOf(invalid)),
                )
            }
        }
        for (owners in listOf(emptyList(), listOf(owner.copy(providerId = "kavita")))) {
            assertThrows(IllegalArgumentException::class.java) {
                BackupConnectionInventory.read(
                    Backup(emptyList(), backupConnections = owners, backupKomgaPageAttempts = listOf(valid)),
                )
            }
        }
    }

    @Test
    fun `duplicate contradictory and newer requests fail preflight`() {
        val valid = BackupKomgaPageEvent(17, 0, "book", 2, 0, 2, 10, 100)
        for ((pages, requests) in listOf(
            (listOf(valid, valid) to emptyList()),
            (listOf(valid) to listOf(valid, valid)),
            (listOf(valid) to listOf(valid.copy(page = 3))),
            (listOf(valid) to listOf(valid.copy(generation = 3))),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                BackupConnectionInventory.read(
                    Backup(
                        emptyList(),
                        backupConnections = listOf(owner),
                        backupKomgaPages = pages,
                        backupKomgaPageAttempts = requests,
                    ),
                )
            }
        }
    }

    @Test
    fun `older backups omit journals and restore cannot target the existing owner`() {
        val old = Backup(emptyList(), backupConnections = listOf(owner))
        val decoded = ProtoBuf.decodeFromByteArray(
            Backup.serializer(),
            ProtoBuf.encodeToByteArray(Backup.serializer(), old),
        )
        assertEquals(
            BackupKomgaProgressJournal.Held(),
            BackupKomgaProgressJournal.remap(decoded, emptyMap(), "a".repeat(64)),
        )
        val exported = BackupKomgaProgressJournal.export(emptyList(), listOf(KomgaPageState(page, null)), emptyList())
        val backup = old.copy(backupKomgaPages = exported.pages)
        for (target in listOf(
            LibraryConnectionProfile(17, "komga", "Same"),
            LibraryConnectionProfile(28, "kavita", "Other"),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                BackupKomgaProgressJournal.remap(backup, mapOf(17L to target), "a".repeat(64))
            }
        }
    }
}

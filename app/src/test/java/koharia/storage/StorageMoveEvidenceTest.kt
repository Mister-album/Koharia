package koharia.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

class StorageMoveEvidenceTest {
    @TempDir lateinit var temporary: Path

    @Test fun `verified atomic file move never reads the original contents`(): Unit = runBlocking {
        val backend = NativeBackend()
        val records = records()
        val journal = StorageMutations(backend, records)
        journal.move(backend.stat("book"), "target")
        assertEquals(0, backend.contentCopies)
        assertEquals(1, backend.probes)
        assertEquals(1, backend.bookMoves)
        assertTrue(records.list("mutations").isEmpty())
        journal.move(backend.stat("target"), "again")
        assertEquals(1, backend.probes)
        assertEquals(0, backend.contentCopies)
        assertEquals("original", backend.memory.contents.getValue("again").decodeToString())
        assertFalse(backend.memory.contents.keys.any { it.startsWith(".koharia/") })
    }

    @Test fun `lost response uses durable identity evidence without replaying move`(): Unit = runBlocking {
        val backend = NativeBackend().apply { loseBookResponse = true }
        val records = records()
        val entry = backend.stat("book")
        assertThrows(StorageFailure::class.java) {
            runBlocking { StorageMutations(backend, records).move(entry, "target") }
        }
        val pending = Json.decodeFromString<StorageMutation>(records.list("mutations").single().payload)
        assertEquals(StorageMoveVerifier.SMB_CONTRACT, pending.moveEvidence?.contract)
        assertEquals(entry.identity, pending.moveEvidence?.identity)
        assertNull(pending.sourceHash)
        assertTrue(StorageMutations(backend, records).reconcile().isEmpty())
        assertEquals(1, backend.bookMoves)
        assertEquals(0, backend.contentCopies)
    }

    @Test fun `external replacement and edits keep ambiguous native move unresolved`(): Unit = runBlocking {
        for (replace in listOf(false, true)) {
            val backend = NativeBackend().apply { loseBookResponse = true }
            val records = records()
            val original = backend.stat("book")
            assertThrows(StorageFailure::class.java) {
                runBlocking { StorageMutations(backend, records).move(original, "target") }
            }
            backend.memory.seed("target", "external")
            if (replace) {
                backend.identities["target"] = "replacement"
                backend.targetVersion = original.version
            }
            assertEquals(1, StorageMutations(backend, records).reconcile().size)
            assertEquals("external", backend.memory.contents.getValue("target").decodeToString())
            assertEquals(1, backend.bookMoves)
            assertEquals(0, backend.contentCopies)
        }
    }

    @Test fun `failed or unstable identity probes preserve complete content verification`(): Unit = runBlocking {
        for (deny in listOf(false, true)) {
            val backend = NativeBackend().apply {
                denyProbe = deny
                unstableProbe = !deny
            }
            val records = records()
            StorageMutations(backend, records).move(backend.stat("book"), "target")
            assertEquals(2, backend.contentCopies)
            assertTrue(records.list("mutations").isEmpty())
            assertEquals("original", backend.memory.contents.getValue("target").decodeToString())
        }
    }

    @Test fun `duplicate native identities require content proof`(): Unit = runBlocking {
        val memory = NativeBackend()
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun stat(path: String) = memory.stat(path).copy(identity = "constant-object")
        }
        val records = records()
        StorageMutations(backend, records).move(backend.stat("book"), "target")
        assertEquals(2, memory.contentCopies)
        assertTrue(records.list("mutations").isEmpty())
    }

    @Test fun `only explicit unsupported move falls back after durable content evidence`(): Unit = runBlocking {
        val backend = NativeBackend().apply { unsupportedBookMove = true }
        val records = records()
        StorageMutations(backend, records, scratchDirectory = temporary.toFile()).move(backend.stat("book"), "target")
        assertTrue(backend.contentCopies >= 3)
        assertFalse(backend.memory.contents.containsKey("book"))
        assertEquals("original", backend.memory.contents.getValue("target").decodeToString())
        assertTrue(records.list("mutations").isEmpty())
    }

    @Test fun `legacy moved phase and unverified identity do not bypass target checksum`(): Unit = runBlocking {
        for (phase in listOf("prepared", "moved")) {
            val backend = NativeBackend()
            val records = records()
            val entry = backend.stat("book")
            backend.move(entry, "target")
            backend.memory.seed("target", "external")
            val operation = StorageMutation(
                kind = "move",
                source = entry,
                destination = "target",
                phase = phase,
                sourceHash = storageDigest("original"),
            )
            records.put("mutations", operation.id, operation)
            assertEquals(1, StorageMutations(backend, records).reconcile().size)
            assertEquals("external", backend.memory.contents.getValue("target").decodeToString())
        }
    }

    @Test fun `unknown evidence and legacy missing hashes remain pending`(): Unit = runBlocking {
        for (evidence in listOf(null, StorageMoveEvidence(version = 99))) {
            val backend = NativeBackend()
            val records = records()
            val entry = backend.stat("book")
            backend.move(entry, "target")
            val operation = StorageMutation(
                kind = "move",
                source = entry,
                destination = "target",
                phase = "moved",
                moveEvidence = evidence,
            )
            records.put("mutations", operation.id, operation)
            assertEquals(1, StorageMutations(backend, records).reconcile().size)
            assertEquals(0, backend.contentCopies)
        }
    }

    @Test fun `readonly and heterogeneous routes cannot advertise verified atomic identities`() {
        val primary = NativeBackend()
        val other = MemoryStorageBackend()
        assertNull(ReadOnlyStorageBackend(primary).capabilities.atomicMoveIdentityContract)
        val router = StorageEndpointRouter(primary, other, { null }, {})
        assertNull(router.capabilities.atomicMoveIdentityContract)
    }

    @Test fun `interrupted probe cleanup stays durable and only removes matching owned data`(): Unit = runBlocking {
        for (conflict in listOf(false, true)) {
            val backend = NativeBackend().apply {
                loseProbeResponse = true
                blockProbeCleanup = true
            }
            val records = records()
            StorageMutations(backend, records).move(backend.stat("book"), "target")
            val probe = Json.decodeFromString<StorageMutation>(records.list("mutations").single().payload)
            assertEquals("move-probe", probe.kind)
            assertEquals(2, backend.contentCopies)
            val remaining = backend.memory.contents.keys.single { it.startsWith(".koharia/") }
            if (conflict) backend.memory.seed(remaining, "external data")
            backend.blockProbeCleanup = false
            assertEquals(if (conflict) 1 else 0, StorageMutations(backend, records).reconcile().size)
            assertEquals(conflict, backend.memory.contents.containsKey(remaining))
            assertEquals("original", backend.memory.contents.getValue("target").decodeToString())
        }
    }

    @Test fun `empty legacy directory cannot be adopted from destination existence`(): Unit = runBlocking {
        val backend = MemoryStorageBackend().apply { directories += "target" }
        val records = records()
        val source = StorageEntry("source", true)
        val operation = StorageMutation(
            kind = "move",
            source = source,
            destination = "target",
            phase = "moved",
            tree = listOf(StorageTreeCopyEntry(source)),
        )
        records.put("mutations", operation.id, operation)
        assertEquals(1, StorageMutations(backend, records).reconcile().size)
        assertTrue(backend.directories.contains("target"))
    }

    @Test fun `legacy content hash still reconciles a valid target`(): Unit = runBlocking {
        val backend = NativeBackend()
        val records = records()
        val entry = backend.stat("book")
        backend.move(entry, "target")
        val operation = StorageMutation(
            kind = "move",
            source = entry,
            destination = "target",
            sourceHash = storageDigest("original"),
        )
        records.put("mutations", operation.id, operation)
        assertTrue(StorageMutations(backend, records).reconcile().isEmpty())
        assertEquals(1, backend.contentCopies)
        assertEquals(1, backend.bookMoves)
    }

    @Test fun `legacy omitted timestamp uses durable row revision for removal`(): Unit = runBlocking {
        val backend = NativeBackend()
        val records = records()
        val entry = backend.stat("book")
        backend.move(entry, "target")
        val operation = StorageMutation(
            kind = "move",
            source = entry,
            destination = "target",
            sourceHash = storageDigest("original"),
        )
        val encoded = Json.encodeToString(operation)
        assertTrue(Json.parseToJsonElement(encoded).jsonObject.containsKey("updatedAt"))
        val legacy = JsonObject(Json.parseToJsonElement(encoded).jsonObject - "updatedAt").toString()
        records.repository.put(
            1,
            "account",
            "root",
            "mutations",
            koharia.domain.storage.StorageRecord(operation.id, legacy, 123),
        )
        assertTrue(StorageMutations(backend, records).reconcile().isEmpty())
        assertTrue(records.list("mutations").isEmpty())
    }

    @Test fun `network failure before server commit never falls back or reissues move`(): Unit = runBlocking {
        val backend = NativeBackend().apply { failBookBeforeMove = true }
        val records = records()
        assertThrows(StorageFailure::class.java) {
            runBlocking {
                StorageMutations(
                    backend,
                    records,
                    scratchDirectory = temporary.toFile(),
                ).move(backend.stat("book"), "target")
            }
        }
        assertEquals(1, StorageMutations(backend, records).reconcile().size)
        assertEquals(1, backend.bookMoves)
        assertEquals(0, backend.contentCopies)
        assertFalse(backend.memory.contents.containsKey("target"))
        assertEquals("original", backend.memory.contents.getValue("book").decodeToString())
    }

    private fun records() = StorageRecordStore(
        StorageSession(1, "account", "root") {
            true
        },
        MemoryStorageRepository(),
        Json,
    )

    private class NativeBackend(
        val memory: MemoryStorageBackend = MemoryStorageBackend().apply { seed("book", "original") },
    ) : LibraryStorageBackend by memory {
        override val capabilities = memory.capabilities.copy(
            atomicMoveIdentityContract = StorageMoveVerifier.SMB_CONTRACT,
        )
        val identities = mutableMapOf("book" to "original-object")
        var contentCopies = 0
        var probes = 0
        var bookMoves = 0
        var loseBookResponse = false
        var unsupportedBookMove = false
        var denyProbe = false
        var unstableProbe = false
        var targetVersion: String? = null
        var loseProbeResponse = false
        var blockProbeCleanup = false
        var failBookBeforeMove = false

        override suspend fun stat(path: String): StorageEntry {
            val entry = memory.stat(path)
            return entry.copy(
                identity = identities.getOrPut(path) { "object-$path" },
                version = if (path == "target") targetVersion ?: entry.version else entry.version,
            )
        }

        override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?) {
            if (path.startsWith(".koharia/") && denyProbe) throw StorageFailure(StorageFailure.Reason.PERMISSION)
            memory.write(path, data, length, expectedVersion)
        }

        override suspend fun move(entry: StorageEntry, destination: String) {
            if (entry.path.startsWith(".koharia/")) probes++ else bookMoves++
            if (entry.path == "book" && unsupportedBookMove) throw StorageFailure(StorageFailure.Reason.UNSUPPORTED)
            if (entry.path == "book" && failBookBeforeMove) throw StorageFailure(StorageFailure.Reason.NETWORK)
            memory.move(memory.stat(entry.path), destination)
            identities[destination] = checkNotNull(identities.remove(entry.path))
            if (entry.path.startsWith(".koharia/") && unstableProbe) identities[destination] = "unstable"
            if (entry.path.startsWith(".koharia/") && loseProbeResponse) {
                throw StorageFailure(StorageFailure.Reason.NETWORK)
            }
            if (entry.path == "book" && loseBookResponse) throw StorageFailure(StorageFailure.Reason.NETWORK)
        }

        override suspend fun delete(entry: StorageEntry) {
            if (entry.path.startsWith(".koharia/") && blockProbeCleanup) {
                throw StorageFailure(StorageFailure.Reason.NETWORK)
            }
            memory.delete(entry)
        }

        override suspend fun copyTo(entry: StorageEntry, output: OutputStream) {
            contentCopies++
            memory.copyTo(memory.stat(entry.path), output)
        }
    }
}

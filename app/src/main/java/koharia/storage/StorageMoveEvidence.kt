package koharia.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import java.util.UUID

@Serializable
data class StorageMoveEvidence(
    val version: Int = 1,
    val contract: String? = null,
    val identity: String? = null,
)

/** Protocol guarantees are necessary; a root-local probe also checks the server's identity implementation. */
internal class StorageMoveVerifier(
    private val backend: LibraryStorageBackend,
    private val records: StorageRecordStore,
) {
    private var verified = false

    suspend fun capture(entry: StorageEntry): StorageMoveEvidence {
        val capabilities = backend.capabilities
        if (entry.directory || entry.identity.isNullOrBlank() || !capabilities.writable || !capabilities.atomicMove ||
            capabilities.atomicMoveIdentityContract != SMB_CONTRACT
        ) {
            return StorageMoveEvidence()
        }
        if (!verified) verified = probe(entry.identity)
        if (!verified) return StorageMoveEvidence()
        val current = backend.stat(entry.path)
        if (!sameObject(entry, current)) throw StorageFailure(StorageFailure.Reason.CONFLICT)
        return StorageMoveEvidence(contract = SMB_CONTRACT, identity = current.identity)
    }

    private suspend fun probe(existingIdentity: String): Boolean {
        val nonce = UUID.randomUUID().toString()
        val source = ".koharia/move-check-$nonce"
        val destination = "$source-renamed"
        val bytes = nonce.encodeToByteArray()
        val operation = StorageMutation(
            id = nonce,
            kind = "move-probe",
            destination = source,
            contentHash = storageDigest(bytes),
            contentSize = bytes.size.toLong(),
            moveEvidence = StorageMoveEvidence(),
        )
        records.put("mutations", operation.id, operation, operation.updatedAt)
        try {
            backend.write(source, bytes.inputStream(), bytes.size.toLong())
            val before = backend.stat(source)
            if (before.identity.isNullOrBlank() || before.identity == existingIdentity ||
                before.directory || before.size != bytes.size.toLong()
            ) {
                return false
            }
            backend.move(before, destination)
            val after = backend.stat(destination)
            val sourceGone = try {
                backend.stat(source)
                false
            } catch (error: StorageFailure) {
                if (error.reason != StorageFailure.Reason.NOT_FOUND) throw error
                true
            }
            return sourceGone && sameObject(before, after) && backend.read(after, 0, bytes.size).contentEquals(bytes)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            return false
        } finally {
            withContext(NonCancellable) { recoverProbe(operation) }
        }
    }

    suspend fun recoverProbe(operation: StorageMutation): Boolean {
        if (operation.moveEvidence != StorageMoveEvidence() ||
            runCatching { UUID.fromString(operation.id).toString() == operation.id }.getOrDefault(false).not() ||
            operation.destination != ".koharia/move-check-${operation.id}" ||
            operation.contentHash != storageDigest(operation.id) ||
            operation.contentSize != operation.id.length.toLong()
        ) {
            return false
        }
        var cleaned = true
        for (path in listOf(operation.destination, "${operation.destination}-renamed")) {
            try {
                val entry = backend.stat(path)
                if (!entry.directory && entry.size == operation.contentSize &&
                    storageDigest(backend.read(entry, 0, entry.size.toInt())) == operation.contentHash
                ) {
                    backend.delete(entry)
                } else {
                    cleaned = false
                }
            } catch (error: StorageFailure) {
                if (error.reason != StorageFailure.Reason.NOT_FOUND) cleaned = false
            } catch (_: IOException) {
                cleaned = false
            }
        }
        if (cleaned) records.remove("mutations", operation.id, operation.updatedAt)
        return cleaned
    }

    companion object {
        const val SMB_CONTRACT = "smb-file-index-creation-rename-v1"

        fun sameObject(expected: StorageEntry, actual: StorageEntry): Boolean =
            !expected.identity.isNullOrBlank() && expected.identity == actual.identity &&
                expected.directory == actual.directory && expected.size == actual.size &&
                expected.version == actual.version
    }
}

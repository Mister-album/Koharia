package koharia.storage

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

object StorageIdentity {
    const val DIRECTORY = ".koharia"
    private const val FILE = ".koharia/storage-id"

    suspend fun read(backend: LibraryStorageBackend): String {
        val entry = backend.stat(FILE)
        if (entry.directory || entry.size !in 1..128) throw StorageFailure(StorageFailure.Reason.UNVERIFIED)
        return backend.read(entry, 0, entry.size.toInt()).decodeToString().trim().also {
            if (runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false).not()) {
                throw StorageFailure(StorageFailure.Reason.UNVERIFIED)
            }
        }
    }

    suspend fun ensure(backend: LibraryStorageBackend): String {
        try {
            return read(backend)
        } catch (error: StorageFailure) {
            if (error.reason != StorageFailure.Reason.NOT_FOUND) throw error
        }
        val directory = try {
            backend.stat(DIRECTORY)
        } catch (error: StorageFailure) {
            if (error.reason != StorageFailure.Reason.NOT_FOUND) throw error
            null
        }
        if (directory == null) {
            try {
                backend.createDirectory(DIRECTORY)
            } catch (error: StorageFailure) {
                if (error.reason != StorageFailure.Reason.CONFLICT || !backend.stat(DIRECTORY).directory) throw error
            }
        } else if (!directory.directory) {
            throw StorageFailure(StorageFailure.Reason.CONFLICT)
        }
        val identity = UUID.randomUUID().toString().encodeToByteArray()
        try {
            backend.write(FILE, identity.inputStream(), identity.size.toLong())
        } catch (error: StorageFailure) {
            if (error.reason != StorageFailure.Reason.CONFLICT) throw error
        }
        return read(backend)
    }

    suspend fun verify(primary: LibraryStorageBackend, internal: LibraryStorageBackend, identity: String) {
        if (read(primary) != identity ||
            read(internal) != identity
        ) {
            throw StorageFailure(StorageFailure.Reason.UNVERIFIED)
        }
        val nonce = UUID.randomUUID().toString()
        val path = ".koharia/verify-$nonce"
        val bytes = nonce.encodeToByteArray()
        // Own this unique file even when the server committed a PUT whose response was lost.
        try {
            primary.write(path, bytes.inputStream(), bytes.size.toLong())
            val observed = internal.stat(path)
            if (observed.size != bytes.size.toLong() || !internal.read(observed, 0, bytes.size).contentEquals(bytes)) {
                throw StorageFailure(StorageFailure.Reason.UNVERIFIED)
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    primary.delete(primary.stat(path))
                } catch (error: StorageFailure) {
                    if (error.reason != StorageFailure.Reason.NOT_FOUND) throw error
                }
            }
        }
    }
}

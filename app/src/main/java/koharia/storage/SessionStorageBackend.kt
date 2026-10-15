package koharia.storage

import java.io.InputStream
import java.io.OutputStream

/** A retained file handle must not start new requests after its connection was replaced. */
class SessionStorageBackend(
    private val delegate: LibraryStorageBackend,
    private val session: StorageSession,
    private val checkWrite: () -> Unit = {},
) : LibraryStorageBackend {
    override val capabilities get() = delegate.capabilities

    private suspend fun <T> active(action: suspend () -> T): T {
        session.checkActive()
        return action().also { session.checkActive() }
    }

    private suspend fun <T> mutate(action: suspend () -> T): T = active {
        checkWrite()
        action().also { checkWrite() }
    }

    override suspend fun stat(path: String) = active { delegate.stat(path) }
    override suspend fun list(path: String) = active { delegate.list(path) }
    override suspend fun read(
        entry: StorageEntry,
        offset: Long,
        length: Int,
    ) = active { delegate.read(entry, offset, length) }
    override suspend fun copyTo(entry: StorageEntry, output: OutputStream) = active { delegate.copyTo(entry, output) }
    override suspend fun createDirectory(path: String) = mutate { delegate.createDirectory(path) }
    override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?) =
        mutate { delegate.write(path, data, length, expectedVersion) }
    override suspend fun move(entry: StorageEntry, destination: String) = mutate { delegate.move(entry, destination) }
    override suspend fun delete(entry: StorageEntry) = mutate { delegate.delete(entry) }
    override fun close() = delegate.close()
}

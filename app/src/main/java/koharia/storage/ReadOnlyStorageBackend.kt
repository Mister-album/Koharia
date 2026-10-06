package koharia.storage

import java.io.InputStream

/** Permission boundary for roots that cannot persist a shared storage identity. */
class ReadOnlyStorageBackend(private val delegate: LibraryStorageBackend) : LibraryStorageBackend by delegate {
    override val capabilities get() = delegate.capabilities.copy(
        writable = false,
        conditionalWrites = false,
        atomicMove = false,
    )
    override suspend fun createDirectory(path: String): Unit = denied()
    override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?): Unit = denied()
    override suspend fun move(entry: StorageEntry, destination: String): Unit = denied()
    override suspend fun delete(entry: StorageEntry): Unit = denied()
    private fun denied(): Nothing = throw StorageFailure(StorageFailure.Reason.PERMISSION)
}

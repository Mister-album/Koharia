package koharia.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream

/** Chooses a transport before a mutation. Only reads may be retried on another verified endpoint. */
class StorageEndpointRouter(
    private val primary: LibraryStorageBackend,
    private val internal: LibraryStorageBackend?,
    private val network: () -> Any?,
    private val verify: suspend (LibraryStorageBackend) -> Unit,
    private val clock: () -> Long = System::nanoTime,
) : LibraryStorageBackend {
    private val mutex = Mutex()
    private var routeNetwork: Any? = null
    private var checkedAt = Long.MIN_VALUE
    private var useInternal = false
    private var primaryVerified = false
    override val capabilities get() = primary.capabilities

    private suspend fun selected(): LibraryStorageBackend {
        val currentNetwork = network()
        return mutex.withLock {
            if (routeNetwork != currentNetwork) {
                routeNetwork = currentNetwork
                primaryVerified = false
                checkedAt = Long.MIN_VALUE
            }
            if (internal == null || currentNetwork == null) {
                if (!primaryVerified) {
                    verify(primary)
                    primaryVerified = true
                }
                return@withLock primary
            }
            if (routeNetwork != currentNetwork || checkedAt == Long.MIN_VALUE ||
                clock() - checkedAt > 30_000_000_000L
            ) {
                routeNetwork = currentNetwork
                checkedAt = clock()
                useInternal = try {
                    verify(internal)
                    true
                } catch (error: IOException) {
                    if (!error.canRoute()) throw error
                    false
                }
            }
            if (useInternal && network() == currentNetwork) {
                internal
            } else {
                if (!primaryVerified) {
                    verify(primary)
                    primaryVerified = true
                }
                primary
            }
        }
    }
    private suspend fun <T> readOnly(block: suspend (LibraryStorageBackend) -> T): T {
        val backend = selected()
        return try {
            block(backend)
        } catch (error: IOException) {
            if (backend === primary || !error.canRoute()) throw error
            mutex.withLock { useInternal = false }
            verify(primary)
            block(primary)
        }
    }
    override suspend fun stat(path: String) = readOnly { it.stat(path) }
    override suspend fun list(path: String) = readOnly { it.list(path) }
    override suspend fun read(
        entry: StorageEntry,
        offset: Long,
        length: Int,
    ) = readOnly { it.read(entry, offset, length) }

    // Streaming copies cannot restart into an output that already contains bytes.
    override suspend fun copyTo(entry: StorageEntry, output: java.io.OutputStream) = selected().copyTo(entry, output)
    override suspend fun createDirectory(path: String) = selected().createDirectory(path)
    override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?) =
        selected().write(path, data, length, expectedVersion)
    override suspend fun move(entry: StorageEntry, destination: String) = selected().move(entry, destination)
    override suspend fun delete(entry: StorageEntry) = selected().delete(entry)
    override fun close() {
        primary.close()
        internal?.close()
    }
}

private fun IOException.canRoute(): Boolean = this !is StorageFailure || reason == StorageFailure.Reason.NETWORK

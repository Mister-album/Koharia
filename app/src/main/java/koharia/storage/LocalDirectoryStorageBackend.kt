package koharia.storage

import android.content.Context
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** SAF and file roots keep their existing document identities and grants. */
class LocalDirectoryStorageBackend(private val context: Context, private val root: UniFile) : LibraryStorageBackend {
    override val capabilities get() = StorageCapabilities(root.canWrite(), true, false, false)

    private fun resolve(path: String): UniFile = StoragePath.normalize(path).split('/').filter(String::isNotEmpty)
        .fold(root) { parent, name -> parent.findFile(name) ?: throw StorageFailure(StorageFailure.Reason.NOT_FOUND) }

    private fun entry(path: String, file: UniFile) = StorageEntry(
        path,
        file.isDirectory,
        file.length(),
        file.lastModified(),
        "${file.length()}:${file.lastModified()}",
        file.uri.toString(),
    )
    override suspend fun stat(
        path: String,
    ) = withContext(Dispatchers.IO) { entry(StoragePath.normalize(path), resolve(path)) }
    override suspend fun list(path: String) = withContext(Dispatchers.IO) {
        val directory = resolve(path)
        check(directory.isDirectory)
        (directory.listFiles() ?: throw StorageFailure(StorageFailure.Reason.PERMISSION)).map {
            entry(StoragePath.child(path, checkNotNull(it.name)), it)
        }
    }
    override suspend fun read(entry: StorageEntry, offset: Long, length: Int) = withContext(Dispatchers.IO) {
        require(offset >= 0 && length >= 0 && offset <= entry.size && length.toLong() <= entry.size - offset)
        if (stat(entry.path).version != entry.version) throw StorageFailure(StorageFailure.Reason.CONFLICT)
        context.contentResolver.openFileDescriptor(resolve(entry.path).uri, "r")!!.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.position(offset)
                val data = ByteArray(length)
                var copied = 0
                while (copied < length) {
                    val count = stream.read(data, copied, length - copied)
                    if (count <= 0) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
                    copied += count
                }
                data
            }
        }
    }
    override suspend fun createDirectory(path: String): Unit = withContext(Dispatchers.IO) {
        val parent = resolve(StoragePath.parent(path))
        if (parent.findFile(path.substringAfterLast('/')) != null) throw StorageFailure(StorageFailure.Reason.CONFLICT)
        checkNotNull(parent.createDirectory(path.substringAfterLast('/')))
        Unit
    }
    override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?): Unit =
        withContext(Dispatchers.IO) {
            val parent = resolve(StoragePath.parent(path))
            val previous = parent.findFile(path.substringAfterLast('/'))
            if (previous != null && (expectedVersion == null || entry(path, previous).version != expectedVersion)) {
                throw StorageFailure(StorageFailure.Reason.CONFLICT)
            }
            val file = previous ?: checkNotNull(parent.createFile(path.substringAfterLast('/')))
            file.openOutputStream().use { output -> check(data.copyTo(output) == length) }
        }
    override suspend fun move(entry: StorageEntry, destination: String): Unit = withContext(Dispatchers.IO) {
        if (stat(entry.path).version != entry.version) throw StorageFailure(StorageFailure.Reason.CONFLICT)
        if (StoragePath.parent(entry.path) != StoragePath.parent(destination)) {
            throw StorageFailure(StorageFailure.Reason.UNSUPPORTED)
        }
        val parent = resolve(StoragePath.parent(destination))
        if (parent.findFile(destination.substringAfterLast('/')) !=
            null
        ) {
            throw StorageFailure(StorageFailure.Reason.CONFLICT)
        }
        check(resolve(entry.path).renameTo(destination.substringAfterLast('/')))
    }
    override suspend fun delete(entry: StorageEntry): Unit = withContext(Dispatchers.IO) {
        require(entry.path.isNotEmpty())
        if (stat(entry.path).version != entry.version) throw StorageFailure(StorageFailure.Reason.CONFLICT)
        check(resolve(entry.path).delete())
    }
    override fun close() = Unit
}

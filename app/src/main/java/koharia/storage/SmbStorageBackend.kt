package koharia.storage

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileAllInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URI

class SmbStorageBackend(
    address: String,
    private val username: String,
    private val password: String,
    private val domain: String,
) :
    LibraryStorageBackend {
    private val endpoint = URI(address).also {
        require(it.scheme == "smb" && it.host != null && it.userInfo == null && it.query == null && it.fragment == null)
    }
    private val segments = StoragePath.normalize(endpoint.path.orEmpty()).split('/').also {
        require(it.first().isNotEmpty()) { "SMB share is required" }
    }
    private val basePath = segments.drop(1).joinToString("\\")
    private val client = newSmbClient()
    private val mutex = Mutex()
    private var connection: Connection? = null
    private var session: Session? = null
    private var disk: DiskShare? = null
    override val capabilities = StorageCapabilities(true, true, false, true)

    private fun share(): DiskShare {
        disk?.takeIf { it.isConnected && connection?.isConnected == true }?.let { return it }
        resetTransport()
        val connected = client.connect(endpoint.host, endpoint.port.takeIf { it > 0 } ?: 445)
        connection = connected
        val authenticated = connected.authenticate(
            if (username.isEmpty()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(
                    username,
                    password.toCharArray(),
                    domain,
                )
            },
        )
        session = authenticated
        return (authenticated.connectShare(segments.first()) as DiskShare).also { disk = it }
    }
    private fun path(path: String) = listOf(basePath, StoragePath.normalize(path).replace('/', '\\'))
        .filter(String::isNotEmpty).joinToString("\\")
    private suspend fun <T> access(block: (DiskShare) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                block(share())
            } catch (error: SMBApiException) {
                val reason = when (error.status.name) {
                    "STATUS_LOGON_FAILURE", "STATUS_WRONG_PASSWORD" -> StorageFailure.Reason.AUTH
                    "STATUS_ACCESS_DENIED" -> StorageFailure.Reason.PERMISSION
                    "STATUS_OBJECT_NAME_NOT_FOUND", "STATUS_OBJECT_PATH_NOT_FOUND", "STATUS_NO_SUCH_FILE" ->
                        StorageFailure.Reason.NOT_FOUND
                    "STATUS_OBJECT_NAME_COLLISION", "STATUS_SHARING_VIOLATION" -> StorageFailure.Reason.CONFLICT
                    "STATUS_NETWORK_NAME_DELETED", "STATUS_USER_SESSION_DELETED", "STATUS_CONNECTION_DISCONNECTED" ->
                        StorageFailure.Reason.NETWORK
                    else -> StorageFailure.Reason.PROTOCOL
                }
                if (reason == StorageFailure.Reason.AUTH || reason == StorageFailure.Reason.NETWORK) resetTransport()
                throw StorageFailure(reason, error)
            } catch (error: java.io.IOException) {
                if (error is StorageFailure) throw error
                resetTransport()
                throw StorageFailure(StorageFailure.Reason.NETWORK, error)
            } catch (error: com.hierynomus.smbj.common.SMBRuntimeException) {
                val networkFailure = disk?.isConnected == false || connection?.isConnected == false ||
                    generateSequence(error.cause) { it.cause }
                        .any { it is java.io.IOException || it is java.util.concurrent.TimeoutException }
                if (networkFailure) resetTransport()
                throw StorageFailure(
                    if (networkFailure) StorageFailure.Reason.NETWORK else StorageFailure.Reason.PROTOCOL,
                    error,
                )
            }
        }
    }
    private fun entry(path: String, info: FileAllInformation): StorageEntry {
        if (info.basicInformation.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L) {
            throw StorageFailure(StorageFailure.Reason.UNSUPPORTED)
        }
        val size = info.standardInformation.endOfFile
        val modified = info.basicInformation.lastWriteTime.toEpochMillis()
        return StorageEntry(
            StoragePath.normalize(path),
            info.standardInformation.isDirectory,
            size,
            modified,
            "$size:${info.basicInformation.lastWriteTime.windowsTimeStamp}",
            info.internalInformation.indexNumber.takeIf { it != 0L }?.let {
                "$it:${info.basicInformation.creationTime.windowsTimeStamp}"
            },
        )
    }
    override suspend fun stat(path: String) = access { entry(path, it.getFileInformation(path(path))) }
    override suspend fun list(path: String) = access { disk ->
        disk.list(path(path)).filter { it.fileName !in setOf(".", "..") }.map { item ->
            if (item.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L) {
                throw StorageFailure(StorageFailure.Reason.UNSUPPORTED)
            }
            StorageEntry(
                StoragePath.child(path, item.fileName),
                item.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                item.endOfFile,
                item.lastWriteTime.toEpochMillis(),
                "${item.endOfFile}:${item.lastWriteTime.windowsTimeStamp}",
                item.fileId.takeIf { it != 0L }?.let { "$it:${item.creationTime.windowsTimeStamp}" },
            )
        }
    }
    override suspend fun read(entry: StorageEntry, offset: Long, length: Int) = access { disk ->
        require(offset >= 0 && length >= 0 && offset <= entry.size && length.toLong() <= entry.size - offset)
        disk.openFile(
            path(entry.path),
            setOf(AccessMask.GENERIC_READ),
            java.util.EnumSet.noneOf(FileAttributes::class.java),
            setOf(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use { file ->
            if (entry(entry.path, file.fileInformation).version !=
                entry.version
            ) {
                throw StorageFailure(StorageFailure.Reason.CONFLICT)
            }
            val result = ByteArray(length)
            var copied = 0
            while (copied < length) {
                val count = file.read(result, offset + copied, copied, length - copied)
                if (count <= 0) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
                copied += count
            }
            result
        }
    }
    override suspend fun createDirectory(path: String) = access { it.mkdir(path(path)) }
    override suspend fun directoryCreationAllowed(path: String): Boolean = try {
        access { disk ->
            disk.openDirectory(
                path(path),
                setOf(AccessMask.FILE_ADD_SUBDIRECTORY),
                java.util.EnumSet.noneOf(FileAttributes::class.java),
                setOf(
                    SMB2ShareAccess.FILE_SHARE_READ,
                    SMB2ShareAccess.FILE_SHARE_WRITE,
                    SMB2ShareAccess.FILE_SHARE_DELETE,
                ),
                SMB2CreateDisposition.FILE_OPEN,
                java.util.EnumSet.noneOf(SMB2CreateOptions::class.java),
            ).use { true }
        }
    } catch (error: StorageFailure) {
        if (error.reason == StorageFailure.Reason.PERMISSION) false else throw error
    }
    override suspend fun write(
        path: String,
        data: InputStream,
        length: Long,
        expectedVersion: String?,
    ) = access { disk ->
        disk.openFile(
            path(path),
            setOf(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
            java.util.EnumSet.noneOf(FileAttributes::class.java),
            java.util.EnumSet.noneOf(SMB2ShareAccess::class.java),
            if (expectedVersion == null) SMB2CreateDisposition.FILE_CREATE else SMB2CreateDisposition.FILE_OPEN,
            setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use { file ->
            if (expectedVersion != null && entry(path, file.fileInformation).version != expectedVersion) {
                throw StorageFailure(StorageFailure.Reason.CONFLICT)
            }
            file.outputStream.use { check(data.copyTo(it) == length) }
            if (file.fileInformation.standardInformation.endOfFile != length) file.setLength(length)
            file.flush()
        }
    }
    override suspend fun move(entry: StorageEntry, destination: String) = access { disk ->
        require(entry.path.isNotEmpty() && StoragePath.normalize(destination).isNotEmpty())
        disk.open(
            path(entry.path),
            setOf(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
            emptySet(),
            emptySet(),
            SMB2CreateDisposition.FILE_OPEN,
            emptySet(),
        ).use { file ->
            if (entry(entry.path, file.fileInformation).version !=
                entry.version
            ) {
                throw StorageFailure(StorageFailure.Reason.CONFLICT)
            }
            file.rename(path(destination), false)
        }
    }
    override suspend fun delete(entry: StorageEntry) = access { disk ->
        require(entry.path.isNotEmpty())
        disk.open(
            path(entry.path),
            setOf(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
            emptySet(),
            emptySet(),
            SMB2CreateDisposition.FILE_OPEN,
            emptySet(),
        ).use { file ->
            if (entry(entry.path, file.fileInformation).version !=
                entry.version
            ) {
                throw StorageFailure(StorageFailure.Reason.CONFLICT)
            }
            file.deleteOnClose()
        }
    }
    private fun resetTransport() {
        runCatching { disk?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        disk = null
        session = null
        connection = null
    }
    override fun close() {
        resetTransport()
        client.close()
    }
}

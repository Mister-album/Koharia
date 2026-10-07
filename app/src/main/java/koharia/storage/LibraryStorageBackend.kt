package koharia.storage

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

@Serializable
enum class LibraryStorageMode { LOCAL, WEBDAV, SMB }

@Serializable
data class StorageEntry(
    val path: String,
    val directory: Boolean,
    val size: Long = 0,
    val modifiedAt: Long = 0,
    val version: String = "",
    val identity: String? = null,
) {
    val name: String get() = path.substringAfterLast('/')
}

data class StorageCapabilities(
    val writable: Boolean,
    val ranges: Boolean,
    val conditionalWrites: Boolean,
    val atomicMove: Boolean,
)

class StorageFailure(val reason: Reason, cause: Throwable? = null) :
    IOException("Storage: ${reason.name}", cause), koharia.connection.ConnectionValidationError {
    override val validationReason get() = when (reason) {
        Reason.AUTH -> koharia.connection.ConnectionAddressVerification.Reason.AUTHENTICATION
        Reason.PERMISSION -> koharia.connection.ConnectionAddressVerification.Reason.PERMISSION
        Reason.NETWORK -> koharia.connection.ConnectionAddressVerification.Reason.UNAVAILABLE
        Reason.PROTOCOL -> koharia.connection.ConnectionAddressVerification.Reason.RESPONSE
        Reason.UNVERIFIED -> koharia.connection.ConnectionAddressVerification.Reason.MISMATCH
        else -> null
    }
    enum class Reason { AUTH, PERMISSION, NOT_FOUND, CONFLICT, UNSUPPORTED, PROTOCOL, NETWORK, SPACE, UNVERIFIED }
}

/** Addresses are transport details. This identity also scopes pending writes and cached bytes. */
data class StorageSession(
    val connectionId: Long,
    val account: String,
    val rootIdentity: String,
    private val active: () -> Boolean,
) {
    fun checkActive() {
        if (!active()) throw CancellationException("Obsolete storage session")
    }
}

interface LibraryStorageBackend : Closeable {
    val capabilities: StorageCapabilities
    suspend fun stat(path: String): StorageEntry
    suspend fun list(path: String): List<StorageEntry>

    /** Returns exactly the requested bytes, or fails. Never silently treats a full response as a range. */
    suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray
    suspend fun copyTo(entry: StorageEntry, output: java.io.OutputStream) {
        var offset = 0L
        while (offset < entry.size) {
            val bytes = read(entry, offset, minOf(256 * 1024L, entry.size - offset).toInt())
            if (bytes.isEmpty()) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            output.write(bytes)
            offset += bytes.size
        }
    }
    suspend fun createDirectory(path: String)

    /** Null means the server cannot report permissions before the create request. */
    suspend fun directoryCreationAllowed(path: String): Boolean? = null

    /** Null expectedVersion means create only. Replacing requires a fresh matching version. */
    suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String? = null)
    suspend fun move(entry: StorageEntry, destination: String)
    suspend fun delete(entry: StorageEntry)
}

object StoragePath {
    fun normalize(path: String): String {
        require('\\' !in path && path.none { it.code < 32 }) { "Invalid storage path" }
        val segments = path.trim('/').split('/').filter(String::isNotEmpty)
        require(segments.none { it == "." || it == ".." }) { "Path escapes storage root" }
        return segments.joinToString("/")
    }
    fun child(parent: String, name: String): String {
        require(name.isNotBlank() && '/' !in name && '\\' !in name)
        return normalize(listOf(parent, name).filter(String::isNotEmpty).joinToString("/"))
    }
    fun parent(path: String): String = normalize(path).substringBeforeLast('/', "")
}

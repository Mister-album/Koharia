package com.hippo.unifile

import android.net.Uri
import android.webkit.MimeTypeMap
import koharia.storage.NetworkStorageRuntime
import koharia.storage.StorageEntry
import koharia.storage.StorageFailure
import koharia.storage.StoragePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Compatibility boundary for existing local parsers. Metadata getters never access the network. */
class RemoteStorageFile(val runtime: NetworkStorageRuntime, var storagePath: String) : UniFile(null) {
    private fun entry(): StorageEntry? = runtime.cached(storagePath)
    override fun getUri(): Uri = runtime.uri(storagePath)
    override fun getName(): String = storagePath.substringAfterLast('/')
    override fun getType(): String = if (isDirectory) {
        "vnd.android.document/directory"
    } else {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.').lowercase())
            ?: "application/octet-stream"
    }
    override fun getFilePath(): String? = null
    override fun getParentFile(): UniFile? = storagePath.takeIf(String::isNotEmpty)?.let {
        runtime.file(StoragePath.parent(it))
    }
    override fun isDirectory() = entry()?.directory == true
    override fun isFile() = entry()?.directory == false
    override fun lastModified() = entry()?.modifiedAt ?: 0
    override fun length() = entry()?.size ?: 0
    override fun canRead() = exists()
    override fun canWrite() = runtime.backend.capabilities.writable
    override fun exists() = entry() != null
    override fun listFiles(): Array<UniFile> = runtime.cachedChildren(storagePath)
        ?.map { runtime.file(it.path) as UniFile }?.toTypedArray()
        ?: throw StorageFailure(StorageFailure.Reason.NOT_FOUND)
    override fun listFiles(
        filter: FilenameFilter,
    ): Array<UniFile> = listFiles().filter { filter.accept(this, it.name) }.toTypedArray()
    override fun findFile(
        displayName: String,
    ): UniFile? = runtime.file(StoragePath.child(storagePath, displayName)).takeIf { it.exists() }
    override fun createDirectory(displayName: String): UniFile = runBlocking(Dispatchers.IO) {
        val path = StoragePath.child(storagePath, displayName)
        runtime.mutations.createDirectory(path)
        runtime.refreshParent(path)
        runtime.snapshot.directory(path, true)
        runtime.file(path)
    }
    override fun createFile(displayName: String): UniFile = runBlocking(Dispatchers.IO) {
        val path = StoragePath.child(storagePath, displayName)
        val temporary = File.createTempFile("storage-create-", ".tmp", runtime.context.cacheDir)
        try {
            runtime.mutations.write(path, temporary, null)
        } finally {
            temporary.delete()
        }
        runtime.refreshParent(path)
        runtime.file(path)
    }
    override fun delete(): Boolean = runBlocking(Dispatchers.IO) {
        val current = entry() ?: return@runBlocking true
        runtime.mutations.delete(current)
        runtime.refreshParent(storagePath)
        true
    }
    override fun renameTo(displayName: String): Boolean = runBlocking(Dispatchers.IO) {
        val destination = StoragePath.child(StoragePath.parent(storagePath), displayName)
        runtime.mutations.move(checkNotNull(entry()), destination)
        runtime.refreshParent(storagePath)
        storagePath = destination
        true
    }
    suspend fun moveTo(parent: RemoteStorageFile): RemoteStorageFile {
        require(parent.runtime === runtime)
        val destination = StoragePath.child(parent.storagePath, name)
        runtime.mutations.move(checkNotNull(entry()), destination)
        runtime.refreshParent(storagePath)
        runtime.refreshParent(destination)
        storagePath = destination
        return this
    }
    override fun openInputStream(): InputStream {
        val current = entry() ?: throw StorageFailure(StorageFailure.Reason.NOT_FOUND)
        val lease = runtime.cache.acquire(current)
        return object : InputStream() {
            private var position = 0L
            private var closed = false
            override fun read(): Int = ByteArray(1).let { if (read(it) < 0) -1 else it[0].toInt() and 255 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                check(!closed)
                require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
                if (length == 0) return 0
                if (position >= current.size) return -1
                val bytes =
                    runBlocking(Dispatchers.IO) { runtime.cache.read(current, position, minOf(length, 256 * 1024)) }
                bytes.copyInto(buffer, offset)
                position += bytes.size
                return bytes.size
            }
            override fun skip(count: Long): Long = count.coerceIn(0, current.size - position).also { position += it }
            override fun close() {
                if (!closed) {
                    closed = true
                    lease.close()
                }
            }
        }
    }
    override fun openOutputStream(): OutputStream = openOutputStream(false)
    override fun openOutputStream(append: Boolean): OutputStream {
        val expected = entry()
        val temporary = File.createTempFile("storage-write-", ".tmp", runtime.context.cacheDir)
        if (append) openInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
        return object : java.io.FilterOutputStream(java.io.FileOutputStream(temporary, append)) {
            private var closed = false
            override fun write(buffer: ByteArray, offset: Int, length: Int) = out.write(buffer, offset, length)
            override fun close() {
                if (closed) return
                closed = true
                try {
                    super.close()
                    runBlocking(Dispatchers.IO) {
                        runtime.mutations.write(storagePath, temporary, expected)
                        runtime.refreshParent(storagePath)
                    }
                } finally {
                    temporary.delete()
                }
            }
        }
    }
    override fun createRandomAccessFile(mode: String): UniRandomAccessFile {
        require(mode == "r") { "Remote random writes are not supported" }
        val current = entry() ?: throw StorageFailure(StorageFailure.Reason.NOT_FOUND)
        val lease = runtime.cache.acquire(current)
        return object : UniRandomAccessFile {
            private var position = 0L
            override fun close() = lease.close()
            override fun getFilePointer() = position
            override fun length() = current.size
            override fun setLength(newLength: Long): Unit = throw StorageFailure(StorageFailure.Reason.UNSUPPORTED)
            override fun seek(pos: Long) {
                require(pos >= 0)
                position = pos
            }
            override fun skipBytes(
                n: Int,
            ): Int = n.toLong().coerceIn(0, (current.size - position).coerceAtLeast(0)).toInt().also {
                position +=
                    it
            }
            override fun read(buffer: ByteArray) = read(buffer, 0, buffer.size)
            override fun read(buffer: ByteArray, offset: Int, length: Int) {
                val bytes = runBlocking(Dispatchers.IO) { runtime.cache.read(current, position, length) }
                if (bytes.size != length) throw java.io.EOFException()
                bytes.copyInto(buffer, offset)
                position += length
            }
            override fun write(buffer: ByteArray): Unit = throw StorageFailure(StorageFailure.Reason.UNSUPPORTED)
            override fun write(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Unit = throw StorageFailure(StorageFailure.Reason.UNSUPPORTED)
        }
    }
}

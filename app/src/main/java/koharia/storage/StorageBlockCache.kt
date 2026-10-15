package koharia.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Shared bounded content cache. DownloadManager owns manual downloads in a separate directory. */
class StorageBlockCache(
    base: File,
    private val session: StorageSession,
    private val backend: LibraryStorageBackend,
    private val resourceIdentity: suspend (StorageEntry) -> String = { it.path },
    private val budget: () -> Long,
) {
    private val namespace = storageDigest("${session.connectionId}/${session.account}/${session.rootIdentity}")
    private val index = StorageCacheIndex.get(base)
    private val verifiedFiles = ConcurrentHashMap<String, Pair<Long, Long>>()
    private suspend fun prepare(entry: StorageEntry): Resource = withContext(Dispatchers.IO) {
        session.checkActive()
        val identity = resourceIdentity(entry)
        session.checkActive()
        Resource(entry, File(index.root, "$namespace/${storageDigest(identity + "\n" + entry.version)}"))
    }

    private inner class Resource(private val entry: StorageEntry, private val directory: File) {
        fun acquire(): Closeable {
            session.checkActive()
            return index.acquire(directory)
        }
        suspend fun read(offset: Long, length: Int): ByteArray = read(entry, directory, offset, length)
        suspend fun prepareComplete(): File = prepareComplete(entry, directory)
    }

    suspend fun acquire(entry: StorageEntry): Closeable = prepare(entry).acquire()
    suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray = prepare(entry).read(offset, length)
    suspend fun prepareComplete(entry: StorageEntry): File = prepare(entry).prepareComplete()

    private suspend fun read(entry: StorageEntry, directory: File, offset: Long, length: Int): ByteArray = withContext(
        Dispatchers.IO,
    ) {
        session.checkActive()
        require(offset >= 0 && length >= 0 && offset <= entry.size)
        val remaining = minOf(length.toLong(), entry.size - offset).toInt()
        val result = ByteArray(remaining)
        index.acquire(directory).use {
            var copied = 0
            while (copied < remaining) {
                currentCoroutineContext().ensureActive()
                val position = offset + copied
                val index = position / BLOCK_SIZE
                val data = block(entry, directory, index)
                val start = (position % BLOCK_SIZE).toInt()
                val count = minOf(data.size - start, remaining - copied)
                if (count <= 0) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
                data.copyInto(result, copied, start, start + count)
                copied += count
            }
        }
        session.checkActive()
        result
    }

    private suspend fun block(entry: StorageEntry, directory: File, blockIndex: Long): ByteArray {
        val file = File(directory, "$blockIndex.block")
        val checksum = File(directory, "$blockIndex.hash")
        return index.exclusive(file) {
            val length = minOf(BLOCK_SIZE.toLong(), entry.size - blockIndex * BLOCK_SIZE).toInt()
            // A range block may be evicted even while its book is open. Serialize the disk read
            // with eviction; the returned byte array remains valid after the file is removed.
            val cached = index.access {
                if (file.isFile && file.length() == length.toLong() && checksum.isFile) {
                    val bytes = file.readBytes()
                    if (bytes.size == length && storageDigest(bytes) == checksum.readText()) {
                        touch(file)
                        bytes
                    } else {
                        remove(file)
                        null
                    }
                } else {
                    remove(file)
                    null
                }
            }
            if (cached != null) return@exclusive cached
            val complete = File(directory, "complete.bin")
            val fullCached = index.access {
                isCompleteValid(complete, entry).also { valid ->
                    if (!valid) {
                        remove(complete)
                        verifiedFiles.remove(complete.path)
                    }
                }
            }
            if (fullCached) {
                val bytes = ByteArray(length)
                RandomAccessFile(complete, "r").use {
                    it.seek(blockIndex * BLOCK_SIZE)
                    it.readFully(bytes)
                }
                index.access { touch(complete) }
                verifiedFiles[complete.path] = complete.lastModified() to File(directory, "complete.ok").lastModified()
                return@exclusive bytes
            }
            val bytes = try {
                backend.read(entry, blockIndex * BLOCK_SIZE, length)
            } catch (error: StorageFailure) {
                if (error.reason != StorageFailure.Reason.UNSUPPORTED) throw error
                val full = prepareComplete(entry, directory)
                val data = ByteArray(length)
                RandomAccessFile(full, "r").use {
                    it.seek(blockIndex * BLOCK_SIZE)
                    it.readFully(data)
                }
                return@exclusive data
            }
            session.checkActive()
            if (bytes.size != length) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            index.access {
                reserve(file, bytes.size.toLong(), budget())
                try {
                    session.checkActive()
                    check(directory.mkdirs() || directory.isDirectory)
                    atomicFile(file, bytes)
                    atomicFile(checksum, storageDigest(bytes).encodeToByteArray())
                    record(file)
                } catch (error: Throwable) {
                    invalidate()
                    throw error
                } finally {
                    release(file)
                }
            }
            bytes
        }
    }

    private suspend fun prepareComplete(entry: StorageEntry, directory: File): File = withContext(Dispatchers.IO) {
        session.checkActive()
        val complete = File(directory, "complete.bin")
        index.exclusive(complete) {
            val lease = index.acquire(directory)
            val temporary = File(directory, "complete.part")
            val coroutine = currentCoroutineContext()
            try {
                if (index.access { isCompleteValid(complete, entry) }) return@exclusive complete
                index.access {
                    remove(complete)
                    session.checkActive()
                    reserve(complete, entry.size, budget())
                    check(directory.mkdirs() || directory.isDirectory)
                }
                java.io.FileOutputStream(temporary).use { stream ->
                    backend.copyTo(
                        entry,
                        object : java.io.FilterOutputStream(stream) {
                            override fun write(value: Int) {
                                checkWrite(1)
                                out.write(value)
                            }
                            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                                checkWrite(length)
                                out.write(buffer, offset, length)
                            }
                            private fun checkWrite(length: Int) {
                                coroutine.ensureActive()
                                session.checkActive()
                                if (temporary.length() > entry.size - length) {
                                    throw StorageFailure(StorageFailure.Reason.PROTOCOL)
                                }
                            }
                        },
                    )
                    stream.fd.sync()
                }
                session.checkActive()
                if (temporary.length() != entry.size) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
                val digest = completeDigest(temporary, entry)
                index.access {
                    session.checkActive()
                    check(temporary.renameTo(complete))
                    atomicFile(File(directory, "complete.ok"), digest.encodeToByteArray())
                    record(complete)
                }
            } catch (error: Throwable) {
                index.invalidate()
                throw error
            } finally {
                try {
                    temporary.delete()
                    index.release(complete)
                } finally {
                    lease.close()
                }
            }
            complete
        }
    }

    private fun completeDigest(file: File, entry: StorageEntry): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return storageDigest(entry.version) + ":" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isCompleteValid(file: File, entry: StorageEntry): Boolean {
        val marker = File(file.parentFile, "complete.ok")
        if (!file.isFile || file.length() != entry.size || !marker.isFile) return false
        val stamp = file.lastModified() to marker.lastModified()
        if (verifiedFiles[file.path] == stamp) return true
        if (marker.readText() != completeDigest(file, entry)) return false
        verifiedFiles[file.path] = stamp
        return true
    }

    companion object {
        const val BLOCK_SIZE = 256 * 1024
    }
}

internal fun storageDigest(value: String) = storageDigest(value.encodeToByteArray())
internal fun storageDigest(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
    .joinToString("") { "%02x".format(it) }

private fun atomicFile(file: File, bytes: ByteArray) {
    val temporary = File(file.parentFile, "${file.name}.part")
    try {
        java.io.FileOutputStream(temporary).use {
            it.write(bytes)
            it.fd.sync()
        }
        check(temporary.renameTo(file))
    } finally {
        temporary.delete()
    }
}

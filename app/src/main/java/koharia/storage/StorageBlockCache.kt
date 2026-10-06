package koharia.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Shared bounded content cache. DownloadManager owns manual downloads in a separate directory. */
class StorageBlockCache(
    private val base: File,
    private val session: StorageSession,
    private val backend: LibraryStorageBackend,
    private val resourceIdentity: (StorageEntry) -> String = { it.path },
    private val budget: () -> Long,
) {
    private val namespace = storageDigest("${session.connectionId}/${session.account}/${session.rootIdentity}")
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val verifiedFiles = ConcurrentHashMap<String, Pair<Long, Long>>()
    private fun directory(entry: StorageEntry): File = File(
        base,
        "$namespace/${storageDigest(resourceIdentity(entry) + "\n" + entry.version)}",
    )

    fun acquire(entry: StorageEntry): Closeable {
        val path = directory(entry).absolutePath
        leases.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
        return object : Closeable {
            private var closed = false

            @Synchronized override fun close() {
                if (!closed) {
                    closed = true
                    leases[path]?.decrementAndGet()
                }
            }
        }
    }

    suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        session.checkActive()
        require(offset >= 0 && length >= 0 && offset <= entry.size)
        val remaining = minOf(length.toLong(), entry.size - offset).toInt()
        val result = ByteArray(remaining)
        acquire(entry).use {
            var copied = 0
            while (copied < remaining) {
                currentCoroutineContext().ensureActive()
                val position = offset + copied
                val index = position / BLOCK_SIZE
                val data = block(entry, index)
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

    private suspend fun block(entry: StorageEntry, index: Long): ByteArray {
        val directory = directory(entry)
        val file = File(directory, "$index.block")
        val checksum = File(directory, "$index.hash")
        return locks.getOrPut(file.path, ::Mutex).withLock {
            val length = minOf(BLOCK_SIZE.toLong(), entry.size - index * BLOCK_SIZE).toInt()
            // A range block may be evicted even while its book is open. Serialize the disk read
            // with eviction; the returned byte array remains valid after the file is removed.
            val cached = eviction.withLock {
                if (file.isFile && checksum.isFile) {
                    val bytes = file.readBytes()
                    if (bytes.size == length && storageDigest(bytes) == checksum.readText()) {
                        file.setLastModified(System.currentTimeMillis())
                        bytes
                    } else {
                        file.delete()
                        checksum.delete()
                        null
                    }
                } else {
                    null
                }
            }
            if (cached != null) return@withLock cached
            val complete = File(directory, "complete.bin")
            if (isCompleteValid(complete, entry)) {
                val bytes = ByteArray(length)
                RandomAccessFile(complete, "r").use {
                    it.seek(index * BLOCK_SIZE)
                    it.readFully(bytes)
                }
                complete.setLastModified(System.currentTimeMillis())
                verifiedFiles[complete.path] = complete.lastModified() to File(directory, "complete.ok").lastModified()
                return@withLock bytes
            }
            val bytes = try {
                backend.read(entry, index * BLOCK_SIZE, length)
            } catch (error: StorageFailure) {
                if (error.reason != StorageFailure.Reason.UNSUPPORTED) throw error
                val full = prepareComplete(entry)
                val data = ByteArray(length)
                RandomAccessFile(full, "r").use {
                    it.seek(index * BLOCK_SIZE)
                    it.readFully(data)
                }
                return@withLock data
            }
            session.checkActive()
            if (bytes.size != length) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            eviction.withLock {
                reserve(bytes.size.toLong())
                check(directory.mkdirs() || directory.isDirectory)
                atomicFile(file, bytes)
                atomicFile(checksum, storageDigest(bytes).encodeToByteArray())
            }
            bytes
        }
    }

    suspend fun prepareComplete(entry: StorageEntry): File = withContext(Dispatchers.IO) {
        session.checkActive()
        val directory = directory(entry)
        val complete = File(directory, "complete.bin")
        locks.getOrPut(complete.path, ::Mutex).withLock {
            if (isCompleteValid(complete, entry)) return@withLock complete
            complete.delete()
            File(directory, "complete.ok").delete()
            session.checkActive()
            eviction.withLock {
                reserve(entry.size)
                check(directory.mkdirs() || directory.isDirectory)
                reservations[complete.absolutePath] = entry.size
            }
            val lease = acquire(entry)
            val temporary = File(directory, "complete.part")
            val coroutine = currentCoroutineContext()
            try {
                temporary.outputStream().use { stream ->
                    backend.copyTo(
                        entry,
                        object : java.io.FilterOutputStream(stream) {
                            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                                coroutine.ensureActive()
                                session.checkActive()
                                if (temporary.length() + length >
                                    entry.size
                                ) {
                                    throw StorageFailure(StorageFailure.Reason.PROTOCOL)
                                }
                                out.write(buffer, offset, length)
                            }
                        },
                    )
                }
                session.checkActive()
                if (temporary.length() != entry.size) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
                check(temporary.renameTo(complete))
                atomicFile(File(directory, "complete.ok"), completeDigest(complete, entry).encodeToByteArray())
            } finally {
                temporary.delete()
                reservations.remove(complete.absolutePath)
                lease.close()
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

    private fun reserve(bytes: Long) {
        check(base.mkdirs() || base.isDirectory)
        val limit = budget()
        if (bytes > limit ||
            base.usableSpace < bytes + 16L * 1024 * 1024
        ) {
            throw StorageFailure(StorageFailure.Reason.SPACE)
        }
        val files = base.walkTopDown().filter {
            it.isFile && (it.extension == "block" || it.name == "complete.bin")
        }.toList()
        var total = files.sumOf(File::length) + reservations.filterKeys {
            it.startsWith(base.absolutePath + File.separator) && !File(it).isFile
        }.values.sum()
        for (file in files.sortedBy(File::lastModified)) {
            if (total + bytes <= limit) break
            if (file.name == "complete.bin" && (leases[file.parentFile!!.absolutePath]?.get() ?: 0) > 0) continue
            val length = file.length()
            if (file.delete()) {
                total -= length
                if (file.name == "complete.bin") {
                    File(file.parentFile, "complete.ok").delete()
                } else {
                    File(file.parentFile, "${file.nameWithoutExtension}.hash").delete()
                }
            }
        }
        if (total + bytes > limit) throw StorageFailure(StorageFailure.Reason.SPACE)
    }

    companion object {
        const val BLOCK_SIZE = 256 * 1024
        private val eviction = Mutex()
        private val leases = ConcurrentHashMap<String, AtomicInteger>()
        private val reservations = ConcurrentHashMap<String, Long>()
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

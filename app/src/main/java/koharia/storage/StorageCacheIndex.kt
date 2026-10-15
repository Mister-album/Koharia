package koharia.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.lang.ref.WeakReference
import java.util.TreeSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One reconstructible ledger per content-cache root, shared by all connection sessions. */
internal class StorageCacheIndex private constructor(val root: File) {
    private data class Item(val file: File, val size: Long, val touched: Long)
    private data class Gate(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val lock = ReentrantLock()
    private val files = mutableMapOf<String, Item>()
    private val oldest = TreeSet(compareBy<Item> { it.touched }.thenBy { it.file.path })
    private val leases = mutableMapOf<String, Int>()
    private val reserved = mutableMapOf<String, Long>()
    private val gates = mutableMapOf<String, Gate>()
    private var initialized = false
    private var total = 0L
    internal var scans = 0
        private set

    fun <T> access(block: StorageCacheIndex.() -> T): T = lock.withLock {
        initialize()
        block()
    }

    suspend fun <T> exclusive(file: File, block: suspend () -> T): T {
        val gate = lock.withLock { gates.getOrPut(file.path, ::Gate).also { it.users++ } }
        try {
            return gate.mutex.withLock { block() }
        } finally {
            lock.withLock { if (--gate.users == 0) gates.remove(file.path) }
        }
    }

    fun acquire(directory: File): Closeable = lock.withLock {
        val path = directory.path
        leases[path] = (leases[path] ?: 0) + 1
        var closed = false
        Closeable {
            lock.withLock {
                if (!closed) {
                    closed = true
                    val remaining = checkNotNull(leases[path]) - 1
                    if (remaining == 0) leases.remove(path) else leases[path] = remaining
                }
            }
        }
    }

    private fun initialize() {
        if (initialized) return
        files.clear()
        oldest.clear()
        total = 0
        check(root.mkdirs() || root.isDirectory)
        scans++
        root.walkTopDown().onFail { _, error -> throw error }.filter { it.isFile }.forEach {
            when {
                it.extension == "block" || it.name == "complete.bin" -> record(it)
                it.name == "complete.part" && File(it.parentFile, "complete.bin").path !in reserved -> {
                    check(it.delete()) { "Unable to remove interrupted cache transfer" }
                }
                it.name.endsWith(".block.part") || it.name.endsWith(".hash.part") || it.name == "complete.ok.part" -> {
                    check(it.delete()) { "Unable to remove interrupted cache write" }
                }
            }
        }
        initialized = true
    }

    fun invalidate() = lock.withLock { initialized = false }

    fun record(file: File) {
        forget(file)
        if (!file.isFile) return
        val item = Item(file, file.length(), file.lastModified())
        files[file.path] = item
        oldest.add(item)
        total += item.size
    }

    private fun forget(file: File) {
        files.remove(file.path)?.let {
            oldest.remove(it)
            total -= it.size
        }
    }

    fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
        record(file)
    }

    fun remove(file: File) {
        if (file.exists() && !file.delete()) {
            record(file)
            throw java.io.IOException("Unable to remove cached content")
        }
        forget(file)
        val marker = marker(file)
        if (marker.exists() && !marker.delete()) throw java.io.IOException("Unable to remove cache marker")
    }

    fun clearUnused(): Int {
        val removable = files.values.map { it.file }.filter {
            it.path !in reserved && (leases[it.parentFile!!.path] ?: 0) == 0
        }
        var removed = 0
        for (file in removable) {
            val existed = file.isFile
            remove(file)
            if (existed) removed++
        }
        return removed
    }

    fun reserve(file: File, bytes: Long, limit: Long) {
        require(bytes >= 0)
        check(file.path !in reserved)
        if (bytes > limit || root.usableSpace - 16L * 1024 * 1024 < bytes) {
            throw StorageFailure(StorageFailure.Reason.SPACE)
        }
        // Reservations may already have a promoted complete.bin while its marker is being written.
        fun used() =
            total + reserved.entries.sumOf { (path, size) -> (size - (files[path]?.size ?: 0)).coerceAtLeast(0) }
        val candidates = oldest.iterator()
        while (used() > limit - bytes && candidates.hasNext()) {
            val item = candidates.next()
            val exists = item.file.exists()
            val protected = item.file.path in reserved ||
                (item.file.name == "complete.bin" && (leases[item.file.parentFile!!.path] ?: 0) > 0)
            if (exists && protected) continue
            if (!exists || item.file.delete()) {
                candidates.remove()
                files.remove(item.file.path)
                total -= item.size
                marker(item.file).delete()
            }
        }
        if (used() > limit - bytes) throw StorageFailure(StorageFailure.Reason.SPACE)
        reserved[file.path] = bytes
    }

    fun release(file: File) = lock.withLock {
        record(file)
        reserved.remove(file.path)
    }

    internal fun measuredBytes() = total
    internal fun reservedBytes() = reserved.values.sum()

    companion object {
        private val roots = mutableMapOf<String, WeakReference<StorageCacheIndex>>()

        @Synchronized fun get(root: File): StorageCacheIndex {
            val canonical = root.canonicalFile
            roots.entries.removeAll { it.value.get() == null }
            return roots[canonical.path]?.get() ?: StorageCacheIndex(canonical).also {
                roots[canonical.path] = WeakReference(it)
            }
        }

        private fun marker(file: File) = File(
            file.parentFile,
            if (file.name == "complete.bin") "complete.ok" else "${file.nameWithoutExtension}.hash",
        )
    }
}

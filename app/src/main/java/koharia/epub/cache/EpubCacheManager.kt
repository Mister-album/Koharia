package koharia.epub.cache

import android.app.Application
import android.text.format.Formatter
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import tachiyomi.core.common.storage.LocalTempCacheDirectoryProvider
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class EpubCacheManager(
    private val application: Application,
    private val preferences: EpubCachePreferences,
) {
    data class ResourceEntry(
        val bytes: ByteArray,
        val mediaType: String?,
    )

    private data class CacheCandidate(
        val files: List<File>,
        val lastModified: Long,
    )

    private val root = LocalTempCacheDirectoryProvider.epubCacheDir(application)
    private val resourcesDir = File(root, "resources").apply { mkdirs() }
    private val booksDir = File(root, "books").apply { mkdirs() }
    private val ioMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transferMutexes = ConcurrentHashMap<String, Mutex>()
    private val transferClients = ConcurrentHashMap<Long, OkHttpClient>()
    private val leasedFiles = Collections.synchronizedSet(mutableSetOf<String>())
    private val leasedPublicationDirs = Collections.synchronizedSet(mutableSetOf<String>())
    private val pendingDeleteFiles = Collections.synchronizedSet(mutableSetOf<String>())
    private val pendingDeleteDirs = Collections.synchronizedSet(mutableSetOf<String>())
    private val activePartialFiles = Collections.synchronizedSet(mutableSetOf<String>())
    private val clearGeneration = AtomicLong(0L)
    private var scheduledTrimJob: Job? = null

    fun completeBookFile(sourceId: Long, publicationKey: String): File? {
        val file = completeBookTarget(sourceId, publicationKey)
        return file.takeIf { it.isFile && it.length() > 0L }
            ?.also(::touch)
    }

    fun acquire(file: File) {
        leasedFiles += file.absolutePath
        touch(file)
    }

    fun release(file: File) {
        leasedFiles -= file.absolutePath
        touch(file)
        if (pendingDeleteFiles.remove(file.absolutePath)) {
            cleanupScope.launch {
                ioMutex.withLock { file.delete() }
            }
        }
    }

    fun acquirePublication(sourceId: Long, publicationKey: String) {
        val directory = resourcePublicationDir(sourceId, publicationKey)
        leasedPublicationDirs += directory.absolutePath
        touch(directory)
    }

    fun releasePublication(sourceId: Long, publicationKey: String) {
        val directory = resourcePublicationDir(sourceId, publicationKey)
        leasedPublicationDirs -= directory.absolutePath
        touch(directory)
        if (pendingDeleteDirs.remove(directory.absolutePath)) {
            cleanupScope.launch {
                ioMutex.withLock { directory.deleteRecursively() }
            }
        }
    }

    suspend fun cacheCompleteBook(
        source: KomgaSource,
        bookUrl: String,
        publicationKey: String,
        acquireLease: Boolean = false,
    ): File? = withContext(Dispatchers.IO) {
        val transferKey = "${source.id}|$publicationKey"
        val transferMutex = transferMutexes.getOrPut(transferKey) { Mutex() }
        transferMutex.withLock {
            completeBookFile(source.id, publicationKey)?.let { file ->
                if (acquireLease) acquire(file)
                return@withLock file
            }
            val generationAtStart = clearGeneration.get()
            val target = completeBookTarget(source.id, publicationKey)
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.part")
            activePartialFiles += temporary.absolutePath
            try {
                val existingBytes = temporary.length().takeIf { it > 0L } ?: 0L
                val call = completeBookTransferClient(source).newCall(
                    source.rawFileRequest(bookUrl, existingBytes.takeIf { it > 0L }),
                )
                runCatching {
                    call.execute().use { response ->
                        check(response.isSuccessful) { "HTTP ${response.code}" }
                        val append = EpubCachePolicy.shouldAppendPartial(
                            existingBytes = existingBytes,
                            responseCode = response.code,
                            contentRange = response.header("Content-Range"),
                        )
                        if (response.code == 206 && existingBytes > 0L && !append) {
                            temporary.delete()
                            error("Invalid EPUB cache Content-Range ${response.header("Content-Range")}")
                        }
                        if (!append && temporary.exists()) temporary.delete()
                        FileOutputStream(temporary, append).use { output ->
                            response.body.byteStream().use { input -> input.copyTo(output) }
                        }
                    }
                    check(temporary.length() > 0L) { "Empty EPUB cache response" }
                    if (generationAtStart != clearGeneration.get()) {
                        temporary.delete()
                        return@runCatching null
                    }
                    ioMutex.withLock {
                        moveAtomically(temporary, target)
                        touch(target)
                        if (acquireLease) acquire(target)
                        trimToSizeLocked()
                    }
                    target
                }.onFailure { error ->
                    logcat(LogPriority.WARN, error) {
                        "Failed to cache complete EPUB sourceId=${source.id} bookUrl=$bookUrl"
                    }
                }.getOrNull()
            } finally {
                activePartialFiles -= temporary.absolutePath
                transferMutexes.remove(transferKey, transferMutex)
            }
        }
    }

    suspend fun getResource(
        sourceId: Long,
        publicationKey: String,
        url: String,
    ): ResourceEntry? = withContext(Dispatchers.IO) {
        val directory = resourcePublicationDir(sourceId, publicationKey)
        val base = url.sha256()
        val body = File(directory, "$base.body")
        val metadata = File(directory, "$base.meta")
        if (!body.isFile || !metadata.isFile) return@withContext null
        runCatching {
            val lines = metadata.readLines()
            if (lines.firstOrNull() != url) return@runCatching null
            touch(directory)
            touch(body)
            touch(metadata)
            ResourceEntry(
                bytes = body.readBytes(),
                mediaType = lines.getOrNull(1)?.ifBlank { null },
            )
        }.getOrNull()
    }

    suspend fun putResource(
        sourceId: Long,
        publicationKey: String,
        url: String,
        mediaType: String?,
        bytes: ByteArray,
    ) = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size > MAX_RESOURCE_BYTES) return@withContext
        ioMutex.withLock {
            val directory = resourcePublicationDir(sourceId, publicationKey).apply { mkdirs() }
            val base = url.sha256()
            val body = File(directory, "$base.body")
            val metadata = File(directory, "$base.meta")
            val temporaryBody = File(directory, "$base.body.tmp")
            val temporaryMetadata = File(directory, "$base.meta.tmp")
            runCatching {
                temporaryBody.writeBytes(bytes)
                temporaryMetadata.writeText("$url\n${mediaType.orEmpty()}\n")
                moveAtomically(temporaryBody, body)
                moveAtomically(temporaryMetadata, metadata)
                touch(directory)
                touch(body)
                touch(metadata)
            }.onFailure { error ->
                temporaryBody.delete()
                temporaryMetadata.delete()
                logcat(LogPriority.WARN, error) { "Failed to cache EPUB resource url=$url" }
            }
        }
        scheduleTrim()
    }

    suspend fun trimToSize() = withContext(Dispatchers.IO) {
        ioMutex.withLock { trimToSizeLocked() }
    }

    suspend fun clear(): Int = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            clearGeneration.incrementAndGet()
            leasedFiles.forEach(pendingDeleteFiles::add)
            leasedPublicationDirs.forEach(pendingDeleteDirs::add)
            cacheFiles()
                .filterNot(::isProtected)
                .count { it.delete() }
                .also { deleteEmptyDirectories() }
        }
    }

    suspend fun clearServer(sourceId: Long): Int = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            clearGeneration.incrementAndGet()
            val sourceBookDir = File(booksDir, sourceId.toString())
            val sourceResourceDir = File(resourcesDir, sourceId.toString())
            leasedFiles.filter { path -> File(path).startsWith(sourceBookDir) }
                .forEach(pendingDeleteFiles::add)
            leasedPublicationDirs.filter { path -> File(path).startsWith(sourceResourceDir) }
                .forEach(pendingDeleteDirs::add)
            (sourceBookDir.walkTopDown() + sourceResourceDir.walkTopDown())
                .filter { it.isFile && !isProtected(it) }
                .count { it.delete() }
                .also { deleteEmptyDirectories() }
        }
    }

    fun sizeBytes(): Long = cacheFiles().sumOf(File::length)

    fun readableSize(): String = Formatter.formatFileSize(application, sizeBytes())

    private fun trimToSizeLocked() {
        var size = sizeBytes()
        val limit = preferences.cacheSizeMb.get()
            .coerceIn(EpubCachePreferences.MIN_CACHE_SIZE_MB, EpubCachePreferences.MAX_CACHE_SIZE_MB)
            .toLong() * BYTES_PER_MB
        if (size <= limit) return

        val resources = resourcesDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".body") && !isProtected(it) }
            .map { body ->
                val metadata = File(body.parentFile, "${body.name.removeSuffix(".body")}.meta")
                CacheCandidate(
                    files = listOf(body, metadata).filter(File::isFile),
                    lastModified = maxOf(body.lastModified(), metadata.lastModified()),
                )
            }
            .sortedBy(CacheCandidate::lastModified)
            .toList()
        val books = booksDir.walkTopDown()
            .filter {
                it.isFile &&
                    !isProtected(it)
            }
            .map { file -> CacheCandidate(listOf(file), file.lastModified()) }
            .sortedBy(CacheCandidate::lastModified)
            .toList()
        for (candidate in resources + books) {
            if (size <= limit) break
            val deletedSize = candidate.files.sumOf { file ->
                val length = file.length()
                if (file.delete()) length else 0L
            }
            size -= deletedSize
        }
        deleteEmptyDirectories()
    }

    @Synchronized
    private fun scheduleTrim() {
        if (scheduledTrimJob?.isActive == true) return
        scheduledTrimJob = cleanupScope.launch {
            delay(TRIM_DEBOUNCE_MS)
            ioMutex.withLock { trimToSizeLocked() }
        }
    }

    private fun completeBookTarget(sourceId: Long, publicationKey: String): File =
        File(File(booksDir, sourceId.toString()), "${publicationKey.sha256()}.epub")

    private fun completeBookTransferClient(source: KomgaSource): OkHttpClient =
        transferClients.getOrPut(source.id) {
            source.client.newBuilder()
                .dispatcher(
                    Dispatcher().apply {
                        maxRequests = 1
                        maxRequestsPerHost = 1
                    },
                )
                .build()
        }

    private fun resourcePublicationDir(sourceId: Long, publicationKey: String): File =
        File(File(resourcesDir, sourceId.toString()), publicationKey.sha256())

    private fun cacheFiles(): List<File> =
        root.walkTopDown().filter(File::isFile).toList()

    private fun isProtected(file: File): Boolean {
        if (file.absolutePath in leasedFiles || file.absolutePath in activePartialFiles) return true
        return leasedPublicationDirs.any { directory -> file.startsWith(File(directory)) }
    }

    private fun deleteEmptyDirectories() {
        root.walkBottomUp()
            .filter { it.isDirectory && it != root && it.listFiles().orEmpty().isEmpty() }
            .forEach(File::delete)
    }

    private fun moveAtomically(source: File, target: File) {
        target.parentFile?.mkdirs()
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun touch(file: File) {
        if (file.exists()) file.setLastModified(System.currentTimeMillis())
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val MAX_RESOURCE_BYTES = 64 * 1024 * 1024
        private const val BYTES_PER_MB = 1024L * 1024L
        private const val TRIM_DEBOUNCE_MS = 1_500L
    }
}

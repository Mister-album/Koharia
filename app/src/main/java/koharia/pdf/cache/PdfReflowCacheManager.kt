package koharia.pdf.cache

import android.app.Application
import com.hippo.unifile.UniFile
import koharia.connection.ConnectionRawDownloadAdapter
import koharia.connection.executeCancellable
import koharia.pdf.extraction.PdfiumExtractionEngine
import koharia.pdf.reflow.PdfReflowBuilder
import koharia.pdf.reflow.PdfReflowManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class PdfRemoteSource(val adapter: ConnectionRawDownloadAdapter, val url: String, val version: String?)

data class PdfReflowArtifact(val directory: File, @Volatile var manifest: PdfReflowManifest) {
    @Volatile internal var progressive: PdfProgressivePublication? = null
    val epub get() = File(directory, if (manifest.isComplete) "book.epub" else "publication.epub")
}

class PdfReflowCacheManager(
    private val application: Application,
    private val root: File = File(application.cacheDir, "pdf-reflow"),
) {
    private val mutex = Mutex()
    private val leaseLock = Any()
    private val leases = mutableMapOf<File, Int>()
    private val progressives = mutableMapOf<File, PdfProgressivePublication>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun findPrepared(sourceId: Long, chapterId: Long, input: UniFile): PdfReflowArtifact? =
        withContext(Dispatchers.IO) {
            if (File(root, "$sourceId/$chapterId").listFiles().orEmpty().none {
                    File(it, "book.epub").isFile && File(it, "manifest.json").isFile
                }
            ) {
                return@withContext null
            }
            get(sourceId, chapterId, revision(hash(input), "persistent"))
        }

    private suspend fun hash(input: UniFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        input.openInputStream().use { stream ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                bytes += count
                require(bytes <= 512L * 1024 * 1024) { "PDF exceeds the conversion size limit" }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun revision(hash: String, scope: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$hash:$ENGINE_VERSION:$scope".toByteArray()).joinToString("") { "%02x".format(it) }

    private suspend fun copyInput(input: UniFile?, remote: PdfRemoteSource?, raw: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val coroutine = currentCoroutineContext()
        var copied = 0L
        fun copy(source: java.io.InputStream) {
            raw.outputStream().buffered().use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    coroutine.ensureActive()
                    val n = source.read(buffer)
                    if (n < 0) break
                    copied += n
                    require(copied <= 512L * 1024 * 1024) { "PDF exceeds the conversion size limit" }
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            }
        }
        if (input != null) {
            input.openInputStream().use(::copy)
        } else {
            val request = checkNotNull(remote)
            request.adapter.rawDownloadClient.newCall(
                request.adapter.rawFileRequest(request.url),
            ).executeCancellable { response ->
                check(response.isSuccessful) { "PDF source HTTP ${response.code}" }
                require(response.body.contentLength() <= 512L * 1024 * 1024) { "PDF source is too large" }
                response.body.byteStream().use(::copy)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal suspend fun prepareProgressive(
        sourceId: Long,
        chapterId: Long,
        input: UniFile?,
        title: String,
        initialPage: Int,
        ephemeral: Boolean,
        remote: PdfRemoteSource?,
    ): PdfReflowArtifact = withContext(Dispatchers.IO) {
        mutex.withLock {
            val parent = File(root, "$sourceId/$chapterId").apply { mkdirs() }
            val scopeKey = if (ephemeral) UUID.randomUUID().toString() else "persistent"
            suspend fun resume(directory: File): PdfReflowArtifact? {
                val existing = get(sourceId, chapterId, directory.name)
                if (existing?.manifest?.isComplete == true) return existing
                existing?.progressive?.takeIf { !it.isClosed }?.let { worker ->
                    worker.awaitPage(initialPage)
                    return existing
                }
                val indexFile = File(directory, "index.json")
                if (!indexFile.isFile || !File(directory, "source.pdf").isFile) return null
                val metadata = Json.decodeFromString<koharia.pdf.reflow.PdfExtractionIndex>(indexFile.readText())
                val manifest = Json.decodeFromString<PdfReflowManifest>(File(directory, "manifest.json").readText())
                val artifact = PdfReflowArtifact(directory, manifest)
                val worker = PdfProgressivePublication(application, artifact, metadata, title, initialPage) {
                    cleanupScope.launch { mutex.withLock { trim(directory) } }
                }
                artifact.progressive = worker
                synchronized(leaseLock) { progressives[directory] = worker }
                try {
                    worker.awaitPage(initialPage)
                    if (!File(directory, "publication.epub").isFile) worker.writeBootstrap()
                    return artifact
                } catch (error: Throwable) {
                    worker.stop()
                    throw error
                }
            }
            if (input != null && !ephemeral && parent.listFiles().orEmpty().isNotEmpty()) {
                val hash = hash(input)
                get(sourceId, chapterId, revision(hash, "persistent"))?.takeIf {
                    it.manifest.isComplete
                }?.let { return@withLock it }
                resume(File(parent, revision(hash, "$scopeKey:chunks4")))?.let { return@withLock it }
            }
            val remoteKey = remote?.version?.let {
                revision("${remote.url}:$it", "remote:chunks4")
            }
            if (input == null && !ephemeral && remoteKey != null) {
                parent.listFiles().orEmpty().firstOrNull {
                    File(it, "remote-key").takeIf(File::isFile)?.readText() == remoteKey
                }?.let { resume(it)?.let { artifact -> return@withLock artifact } }
            }
            val stage = File(parent, "stage-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val raw = File(stage, "source.pdf")
                val hash = copyInput(input, remote, raw)
                val revision = revision(hash, "$scopeKey:chunks4")
                val destination = File(parent, revision)
                resume(destination)?.let { return@withLock it }
                val index = PdfiumExtractionEngine(application).extractPages(raw, stage, emptyList())
                val manifest = PdfReflowManifest(
                    revision,
                    hash,
                    index.pageCount,
                    (0 until index.pageCount).map { PdfProgressivePublication.pageAnchor(it, index.pageCount) },
                    isComplete = false,
                    chunkSize = PdfProgressivePublication.CHUNK_SIZE,
                )
                File(stage, "manifest.json").writeText(Json.encodeToString(manifest))
                if (ephemeral) File(stage, "ephemeral").writeText("")
                if (remoteKey != null) File(stage, "remote-key").writeText(remoteKey)
                synchronized(leaseLock) {
                    check(destination !in leases) { "PDF preparation is already in use" }
                    if (destination.exists()) destination.deleteRecursively()
                    check(stage.renameTo(destination)) { "Cannot publish PDF preparation" }
                }
                checkNotNull(resume(destination)).also { trim(destination) }
            } finally {
                if (stage.exists()) stage.deleteRecursively()
            }
        }
    }

    suspend fun prepare(
        sourceId: Long,
        chapterId: Long,
        input: UniFile?,
        title: String,
        ephemeral: Boolean = false,
        remote: PdfRemoteSource? = null,
        onProgress: (Int, Int) -> Unit,
    ): PdfReflowArtifact =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val scope = File(root, "$sourceId/$chapterId").apply { mkdirs() }
                if (input != null && !ephemeral) {
                    findPrepared(sourceId, chapterId, input)?.let { return@withLock it }
                }
                val remoteKey = remote?.version?.let { version ->
                    MessageDigest.getInstance("SHA-256").digest("${remote.url}:$version:$ENGINE_VERSION".toByteArray())
                        .joinToString("") { "%02x".format(it) }
                }
                if (input == null && !ephemeral && remoteKey != null) {
                    scope.listFiles().orEmpty().firstOrNull {
                        File(it, "remote-key").takeIf(File::isFile)?.readText() == remoteKey
                    }?.let { cached -> get(sourceId, chapterId, cached.name)?.let { return@withLock it } }
                }
                onProgress(0, 0)
                val stage = File(scope, "stage-${UUID.randomUUID()}").apply { mkdirs() }
                try {
                    val raw = File(stage, "source.pdf")
                    val hash = copyInput(input, remote, raw)
                    val coroutine = currentCoroutineContext()
                    val scopeKey = if (ephemeral) UUID.randomUUID().toString() else "persistent"
                    val revision = revision(hash, scopeKey)
                    get(sourceId, chapterId, revision)?.let { return@withLock it }
                    PdfiumExtractionEngine(application).extract(raw, stage, onProgress)
                    PdfReflowBuilder.build(stage, title, hash, revision) { coroutine.ensureActive() }
                    if (ephemeral) File(stage, "ephemeral").writeText("")
                    if (remoteKey != null) File(stage, "remote-key").writeText(remoteKey)
                    coroutine.ensureActive()
                    raw.delete()
                    val destination = File(scope, revision)
                    synchronized(leaseLock) {
                        if (destination.exists() && destination !in leases) destination.deleteRecursively()
                        check(!destination.exists() && stage.renameTo(destination)) {
                            "Cannot publish PDF reflow cache"
                        }
                    }
                    trim(destination)
                    checkNotNull(get(sourceId, chapterId, revision))
                } finally {
                    if (stage.exists()) stage.deleteRecursively()
                }
            }
        }

    fun get(sourceId: Long, chapterId: Long, revision: String): PdfReflowArtifact? {
        if (!revision.matches(Regex("[a-f0-9]{64}"))) return null
        val directory = File(root, "$sourceId/$chapterId/$revision")
        synchronized(leaseLock) {
            progressives[directory]?.takeIf { !it.isClosed }?.let {
                directory.setLastModified(System.currentTimeMillis())
                return it.artifact
            }
        }
        if (!File(directory, "book.epub").isFile || !File(directory, "manifest.json").isFile) return null
        val manifest =
            runCatching {
                Json.decodeFromString<PdfReflowManifest>(File(directory, "manifest.json").readText())
            }.getOrNull()
                ?: return null
        if (manifest.revision != revision) return null
        if (!manifest.isComplete) return null
        directory.setLastModified(System.currentTimeMillis())
        return PdfReflowArtifact(directory, manifest)
    }

    fun acquire(artifact: PdfReflowArtifact) = synchronized(leaseLock) {
        check(artifact.epub.isFile && (artifact.progressive?.isClosed != true || artifact.manifest.isComplete)) {
            "PDF reflow cache is no longer available"
        }
        leases[artifact.directory] = leases.getOrDefault(artifact.directory, 0) + 1
    }

    fun release(artifact: PdfReflowArtifact) = synchronized(leaseLock) {
        val count = leases.getOrDefault(artifact.directory, 0) - 1
        if (count > 0) {
            leases[artifact.directory] = count
        } else {
            leases.remove(artifact.directory)
            val worker = artifact.progressive
            if (worker != null) {
                worker.stop()
                cleanupScope.launch {
                    worker.stopAndJoin()
                    synchronized(leaseLock) {
                        if (progressives[artifact.directory] === worker && artifact.directory !in leases) {
                            progressives.remove(artifact.directory)
                            if (File(artifact.directory, "ephemeral").exists()) artifact.directory.deleteRecursively()
                        }
                    }
                    mutex.withLock { trim(File(root, ".no-active-publication")) }
                }
            } else if (File(artifact.directory, "ephemeral").exists()) {
                artifact.directory.deleteRecursively()
            }
        }
    }

    suspend fun clear(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val targets = synchronized(leaseLock) { directories().filter { it !in leases } }
            for (directory in targets) {
                synchronized(leaseLock) { progressives[directory] }?.stopAndJoin()
            }
            synchronized(leaseLock) {
                targets.count { directory ->
                    if (directory in leases) {
                        false
                    } else {
                        progressives.remove(directory)
                        directory.deleteRecursively()
                    }
                }
            }
        }
    }

    private fun directories(): List<File> = root.listFiles().orEmpty().flatMap { source ->
        source.listFiles().orEmpty().flatMap { chapter -> chapter.listFiles().orEmpty().filter(File::isDirectory) }
    }

    private fun trim(keep: File) = synchronized(leaseLock) {
        val directories = directories()
        val sizes = directories.associateWith { directory ->
            directory.walkTopDown().filter(File::isFile).sumOf(File::length)
        }
        var total = sizes.values.sum()
        directories.sortedBy(File::lastModified).forEach { directory ->
            if (directory != keep && directory !in leases &&
                (total > 512L * 1024 * 1024 || File(directory, "ephemeral").exists())
            ) {
                if (progressives.containsKey(directory)) return@forEach
                if (directory.deleteRecursively()) total -= sizes.getValue(directory)
            }
        }
    }

    companion object {
        const val ENGINE_VERSION = "pdfium-core-2.0.3:ir3:reflow5:epub4"
    }
}

@file:Suppress("ktlint:standard:max-line-length")

package koharia.pdf.cache

import android.app.Application
import koharia.pdf.extraction.PdfiumExtractionEngine
import koharia.pdf.reflow.PdfBox
import koharia.pdf.reflow.PdfExtractionIndex
import koharia.pdf.reflow.PdfMappedBlock
import koharia.pdf.reflow.PdfReflowBuilder
import koharia.pdf.reflow.PdfReflowManifest
import koharia.pdf.reflow.PdfSourceSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.jsoup.parser.Parser
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable
private data class PdfPreparedChunk(
    val manifest: PdfReflowManifest,
    val assets: List<String>,
    val textCharacters: Int,
)

/** Stable resource addresses. Each completed chunk and each published ZIP is immutable. */
internal class PdfProgressivePublication(
    private val application: Application,
    val artifact: PdfReflowArtifact,
    private val metadata: PdfExtractionIndex,
    private val title: String,
    initialPage: Int,
    private val onFinished: () -> Unit = {},
) {
    private val directory get() = artifact.directory
    private val chunkCount = (metadata.pageCount + CHUNK_SIZE - 1) / CHUNK_SIZE
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Int>(Channel.UNLIMITED)
    private val waiters = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val chunks = ConcurrentHashMap<Int, PdfReflowManifest>()
    private val mutableComplete = MutableStateFlow(false)
    val complete = mutableComplete.asStateFlow()
    private val mutableOutcome = MutableStateFlow<PdfPreparationOutcome?>(null)
    val outcome = mutableOutcome.asStateFlow()

    @Volatile private var background = false

    @Volatile private var lastInteraction = 0L

    @Volatile private var anchor = initialPage.coerceIn(0, metadata.pageCount - 1) / CHUNK_SIZE

    @Volatile var isClosed = false
        private set

    @Volatile private var failure: Throwable? = null
    private var characters = 0

    init {
        repeat(chunkCount) { chunk ->
            val record = File(directory, "chunks/$chunk.json")
            if (record.isFile && File(directory, "text/chunk-$chunk.xhtml").isFile) {
                runCatching {
                    Json.decodeFromString<PdfPreparedChunk>(record.readText())
                }.getOrNull()?.takeIf { saved ->
                    File(directory, "notes/chunk-$chunk.xhtml").isFile &&
                        saved.assets.all { File(directory, "images/$it").isFile }
                }?.let {
                    chunks[chunk] = it.manifest
                    waiters[chunk] = CompletableDeferred(Unit)
                    characters += it.textCharacters
                }
            }
        }
        if (chunks.isNotEmpty()) publishManifest(false)
    }

    private val worker = scope.launch {
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (chunks.size == chunkCount) {
                    finishPublication()
                    break
                }
                var requested = requests.tryReceive().getOrNull()
                if (requested == null && background) {
                    val remaining = 300L - (android.os.SystemClock.elapsedRealtime() - lastInteraction)
                    if (remaining > 0) {
                        requested = withTimeoutOrNull(remaining) { requests.receive() }
                        if (requested == null) continue
                    }
                }
                val next = requested ?: if (background) {
                    (0 until chunkCount).filter { !chunks.containsKey(it) }.minByOrNull { kotlin.math.abs(it - anchor) }
                } else {
                    null
                }
                val chunk = next ?: requests.receive()
                if (chunk !in 0 until chunkCount || chunks.containsKey(chunk)) continue
                buildChunk(chunk)
                waiters.computeIfAbsent(chunk) { CompletableDeferred() }.complete(Unit)
            }
        } catch (error: Throwable) {
            failure = error
            isClosed = true
            waiters.values.forEach { it.completeExceptionally(error) }
            if (error is CancellationException) throw error
            mutableOutcome.value = PdfPreparationOutcome.Failed(error)
            logcat(LogPriority.WARN, error) { "PDF preparation stopped; completed chunks remain available" }
        }
    }

    suspend fun awaitPage(page: Int) = awaitChunk(page.coerceIn(0, metadata.pageCount - 1) / CHUNK_SIZE)

    suspend fun awaitChunk(chunk: Int) {
        require(chunk in 0 until chunkCount)
        if (chunks.containsKey(chunk)) return
        failure?.let { throw it }
        check(!isClosed) { "PDF preparation was closed" }
        anchor = chunk
        val waiter = waiters.computeIfAbsent(chunk) { CompletableDeferred() }
        requests.send(chunk)
        failure?.let { waiter.completeExceptionally(it) }
        waiter.await()
    }

    fun startBackground() {
        if (isClosed || background || mutableComplete.value) return
        background = true
        requests.trySend(-1)
    }

    fun onReadingInteraction() {
        lastInteraction = android.os.SystemClock.elapsedRealtime()
    }

    fun stop() {
        isClosed = true
        scope.cancel()
    }

    suspend fun stopAndJoin() {
        stop()
        worker.cancelAndJoin()
    }

    suspend fun resource(path: String): File {
        val chunk = chunkResource.matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()
            ?: noteResource.matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()
            ?: imageResource.matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()?.div(CHUNK_SIZE)
            ?: error("Unknown PDF resource")
        awaitChunk(chunk)
        return File(directory, path.removePrefix("EPUB/"))
    }

    fun serves(path: String) = chunkResource.matches(path) || noteResource.matches(path) || imageResource.matches(path)

    private suspend fun buildChunk(chunk: Int) {
        currentCoroutineContext().ensureActive()
        val work = File(directory, "work/$chunk").apply { mkdirs() }
        val pages = (chunk * CHUNK_SIZE until minOf((chunk + 1) * CHUNK_SIZE, metadata.pageCount)).toList()
        val extracted = PdfiumExtractionEngine(
            application,
        ).extractPages(File(directory, "source.pdf"), work, pages, metadata)
        characters += extracted.textCharacters
        require(characters <= 2_000_000) { "PDF text exceeds the conversion limit" }
        val coroutine = currentCoroutineContext()
        val map = PdfReflowBuilder.build(work, title, artifact.manifest.sourceHash, artifact.manifest.revision) {
            coroutine.ensureActive()
        }
        val href = "EPUB/text/chunk-$chunk.xhtml"
        val assets = mutableListOf<String>()
        ZipFile(File(work, "book.epub")).use { zip ->
            val text = zip.entries().asSequence().filter { it.name.startsWith("EPUB/text/") }
                .joinToString("") { entry ->
                    val document = Jsoup.parse(
                        zip.getInputStream(entry).bufferedReader().readText(),
                        "",
                        Parser.xmlParser(),
                    )
                    document.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
                        .escapeMode(Entities.EscapeMode.xhtml).prettyPrint(false)
                    document.selectFirst("body")?.html().orEmpty()
                }.replace("../notes.xhtml#", "../notes/chunk-$chunk.xhtml#")
            zip.entries().asSequence().filter { it.name.startsWith("EPUB/images/") }.forEach { entry ->
                val target = File(directory, entry.name.removePrefix("EPUB/"))
                check(target.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()))
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, "${target.name}.tmp")
                zip.getInputStream(entry).use { input -> temporary.outputStream().use(input::copyTo) }
                java.nio.file.Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                assets += target.name
            }
            val notes = zip.getEntry("EPUB/notes.xhtml")?.let { zip.getInputStream(it).bufferedReader().readText() }
                ?.replace("href=\"style.css\"", "href=\"../style.css\"")
                ?: PdfReflowBuilder.xhtml(title, "", "../style.css")
            writeFile("notes/chunk-$chunk.xhtml", notes)
            if (!File(directory, "style.css").isFile) {
                writeFile("style.css", zip.getInputStream(zip.getEntry("EPUB/style.css")).bufferedReader().readText())
            }
            val grouped = Jsoup.parseBodyFragment(text)
            grouped.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml).prettyPrint(false)
            val body = grouped.body()
            var section: org.jsoup.nodes.Element? = null
            body.childNodes().toList().forEach { node ->
                if (node is org.jsoup.nodes.Element && node.id().startsWith("pdf-page-")) {
                    section = org.jsoup.nodes.Element("section").attr("id", node.id())
                    node.remove()
                    body.appendChild(checkNotNull(section))
                } else {
                    section?.appendChild(node)
                }
            }
            writeFile("text/chunk-$chunk.xhtml", PdfReflowBuilder.xhtml(title, body.html(), "../style.css"))
        }
        val mapped = map.copy(
            blocks = pages.flatMap { page ->
                listOf(pageAnchor(page, metadata.pageCount)) +
                    map.blocks.filter { it.source.page == page }.map { it.copy(href = href) }
            },
        )
        writeFile("chunks/$chunk.json", Json.encodeToString(PdfPreparedChunk(mapped, assets, extracted.textCharacters)))
        chunks[chunk] = mapped
        publishManifest(false)
        // Generated resources are retained once; the per-chunk ZIP is only a packaging intermediate.
        File(work, "book.epub").delete()
        work.listFiles().orEmpty().filter { it.extension == "png" }.forEach(File::delete)
        val bytes = directory.walkTopDown().filter { it.isFile && it.name != "source.pdf" }.sumOf(File::length)
        require(bytes <= 512L * 1024 * 1024) { "PDF preparation exceeds the cache budget" }
    }

    private fun publishManifest(finished: Boolean) {
        val manifest = artifact.manifest.copy(
            blocks = (0 until chunkCount).flatMap { chunk ->
                chunks[chunk]?.blocks ?: (chunk * CHUNK_SIZE until minOf((chunk + 1) * CHUNK_SIZE, metadata.pageCount))
                    .map { pageAnchor(it, metadata.pageCount) }
            },
            isComplete = finished,
            availablePages = chunks.keys.sorted().flatMap { chunk ->
                (chunk * CHUNK_SIZE until minOf((chunk + 1) * CHUNK_SIZE, metadata.pageCount)).toList()
            },
        )
        if (finished) writeFile("manifest.json", Json.encodeToString(manifest))
        artifact.manifest = manifest
    }

    private fun writeFile(path: String, text: String) {
        val target = File(directory, path)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(text)
        java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    suspend fun writeBootstrap() {
        val context = currentCoroutineContext()
        writePublication(File(directory, "publication.epub"), false) { context.ensureActive() }
    }

    private suspend fun finishPublication() {
        val context = currentCoroutineContext()
        val readableCharacters = chunks.values.sumOf { chunk ->
            chunk.blocks.filter { it.id.substringAfterLast("-r", "").toIntOrNull() != null }.sumOf { it.context.length }
        }
        require(readableCharacters >= 80) { "This PDF cannot be reliably reflowed" }
        writePublication(File(directory, "book.epub"), true) { context.ensureActive() }
        context.ensureActive()
        publishManifest(true)
        File(directory, "source.pdf").delete()
        mutableComplete.value = true
        mutableOutcome.value = PdfPreparationOutcome.Completed
        onFinished()
    }

    private fun writePublication(target: File, finished: Boolean, checkCancellation: () -> Unit) {
        val temporary = File(directory, "${target.name}.tmp")
        val imageFiles = File(directory, "images").listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName)
        ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray, stored: Boolean = false) {
                checkCancellation()
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            fun file(name: String, file: File) {
                val crc = CRC32()
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        checkCancellation()
                        val n = input.read(buffer)
                        if (n < 0) break
                        crc.update(buffer, 0, n)
                    }
                }
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = file.length()
                    this.crc = crc.value
                }
                zip.putNextEntry(entry)
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        checkCancellation()
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                    }
                }
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray(), true)
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            )
            val items = (0 until chunkCount).joinToString("") {
                "<item id=\"c$it\" href=\"text/chunk-$it.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"n$it\" href=\"notes/chunk-$it.xhtml\" media-type=\"application/xhtml+xml\"/>"
            } +
                imageFiles.mapIndexed { i, image ->
                    "<item id=\"i$i\" href=\"images/${image.name}\" media-type=\"image/png\"/>"
                }.joinToString("")
            val spine = (0 until chunkCount).joinToString("") { "<itemref idref=\"c$it\"/>" }
            val name = PdfReflowBuilder.xml(title)
            put(
                "EPUB/package.opf",
                """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:sha256:${artifact.manifest.revision}</dc:identifier><dc:title>$name</dc:title><dc:language>und</dc:language><meta property="dcterms:modified">2000-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="css" href="style.css" media-type="text/css"/>$items</manifest><spine>$spine</spine></package>""".toByteArray(),
            )
            val toc = metadata.outlines.map { outline ->
                "<li><a href=\"text/chunk-${outline.page / CHUNK_SIZE}.xhtml#pdf-page-${outline.page}\">${PdfReflowBuilder.xml(
                    outline.title,
                )}</a></li>"
            }.ifEmpty { listOf("<li><a href=\"text/chunk-0.xhtml\">$name</a></li>") }.joinToString("")
            put(
                "EPUB/nav.xhtml",
                PdfReflowBuilder.xhtml(title, "<nav epub:type=\"toc\"><ol>$toc</ol></nav>", "style.css").toByteArray(),
            )
            file("EPUB/style.css", File(directory, "style.css"))
            repeat(chunkCount) { chunk ->
                for (folder in listOf("text", "notes")) {
                    val path = "$folder/chunk-$chunk.xhtml"
                    val content = File(directory, path)
                    if (content.isFile) {
                        file("EPUB/$path", content)
                    } else {
                        check(!finished)
                        put("EPUB/$path", PdfReflowBuilder.xhtml(title, "", "../style.css").toByteArray())
                    }
                }
            }
            imageFiles.forEach { file("EPUB/images/${it.name}", it) }
        }
        java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val CHUNK_SIZE = 4
        private val chunkResource = Regex("EPUB/text/chunk-(\\d+)\\.xhtml")
        private val noteResource = Regex("EPUB/notes/chunk-(\\d+)\\.xhtml")
        private val imageResource = Regex("EPUB/images/page-(\\d+)(?:-image-\\d+)?\\.png")

        fun pageAnchor(page: Int, total: Int) = PdfMappedBlock(
            "pdf-page-$page",
            "EPUB/text/chunk-${page / CHUNK_SIZE}.xhtml",
            PdfSourceSpan(page.coerceIn(0, total - 1), 0, 0, PdfBox(0f, 0f, 0f, 0f)),
            "",
        )
    }
}

package koharia.pdf

import android.app.Application
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.reader.loader.PdfPageLoader
import koharia.epub.model.EpubOpenRequest
import koharia.epub.service.LocalEpubPublicationService
import koharia.pdf.cache.PdfReflowCacheManager
import koharia.pdf.extraction.PdfiumExtractionEngine
import koharia.pdf.reflow.PdfPageFacts
import koharia.pdf.reflow.PdfProgressMapper
import koharia.pdf.reflow.PdfReflowBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class PdfReflowDeviceTest {
    private val application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Test
    fun suppliedBookCanBeConverted() = runBlocking(Dispatchers.IO) {
        val input = File(application.filesDir, "pdf-source-sample.pdf")
        assumeTrue("Optional real-book fixture is not installed", input.isFile)
        val output = File(application.filesDir, "pdf-sample-reflow").apply { mkdirs() }
        val started = android.os.SystemClock.elapsedRealtime()
        val milestones = org.json.JSONObject()
        val index = PdfiumExtractionEngine(application).extract(input, output) { page, _ ->
            if (page in listOf(1, 8, 16, 32, 64, 128, 256)) {
                milestones.put(page.toString(), android.os.SystemClock.elapsedRealtime() - started)
            }
        }
        val extractedAt = android.os.SystemClock.elapsedRealtime()
        val manifest = PdfReflowBuilder.build(output, "PDF reflow sample", "c".repeat(64), "d".repeat(64))
        val builtAt = android.os.SystemClock.elapsedRealtime()
        LocalEpubPublicationService(application).open(
            EpubOpenRequest(
                1,
                1,
                1,
                "PDF profile",
                null,
                File(output, "book.epub").toURI().toString(),
                EpubOpenRequest.OpenSource.LOCAL,
            ),
            null,
        ).useSession { assertTrue(it.publication.readingOrder.isNotEmpty()) }
        val openedAt = android.os.SystemClock.elapsedRealtime()
        File(output, "test-result.json").writeText(
            org.json.JSONObject()
                .put("pageCount", index.pageCount).put("characters", index.textCharacters)
                .put("mappedBlocks", manifest.blocks.size)
                .put("extractionMs", extractedAt - started)
                .put("rebuildAndPackageMs", builtAt - extractedAt)
                .put("publicationOpenMs", openedAt - builtAt)
                .put("pageMilestonesMs", milestones)
                .put("elapsedMs", openedAt - started).toString(),
        )
        assertEquals(296, index.pageCount)
        assertTrue(index.textCharacters > 50000)
        assertTrue(manifest.blocks.any { it.source.page == 295 })
        // The real book's ordinary page used to be mistaken for columns because of low CJK punctuation.
        val ordinary = Json.decodeFromString<PdfPageFacts>(File(output, "page-162.json").readText())
        assertEquals(null, ordinary.fallbackAsset)
        for (page in listOf(8, 41, 105, 135, 147, 172)) {
            val annotated = Json.decodeFromString<PdfPageFacts>(File(output, "page-$page.json").readText())
            assertEquals("Ruby must remain text on page $page", null, annotated.fallbackAsset)
            assertTrue(koharia.pdf.reflow.PdfInlineLayout.analyze(annotated).ruby.isNotEmpty())
        }
    }

    @Test
    fun originalPdfUsesOpaquePaper() = runBlocking {
        val directory = Files.createTempDirectory(application.cacheDir.toPath(), "pdf-paper-").toFile()
        try {
            val pdf = createSample(directory)
            val loader = PdfPageLoader(application, checkNotNull(UniFile.fromFile(pdf)))
            try {
                val bitmap = checkNotNull(loader.getPages().first().bitmap).invoke()
                try {
                    assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
                } finally {
                    bitmap.recycle()
                }
            } finally {
                loader.recycle()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun extractsStylesImagesAndOpensDerivedEpubInReadium() = runBlocking(Dispatchers.IO) {
        val directory = Files.createTempDirectory(application.cacheDir.toPath(), "pdf-reflow-test-").toFile()
        try {
            val pdf = createSample(directory)
            val index = PdfiumExtractionEngine(application).extract(pdf, directory) { _, _ -> }
            assertEquals(3, index.pageCount)
            val first = Json.decodeFromString<PdfPageFacts>(File(directory, "page-0.json").readText())
            assertTrue(first.glyphs.joinToString("") { it.text }.contains("Chapter"))
            assertTrue(first.styles.any { it.size >= 23f })
            assertTrue(first.styles.any { it.color == Color.RED })
            assertEquals("PDF comment", first.annotations.single().text)
            assertTrue(first.styles.any { (it.weight ?: 0) >= 600 || it.font?.contains("Bold", true) == true })
            val illustration = Json.decodeFromString<PdfPageFacts>(File(directory, "page-1.json").readText())
            assertNotNull(illustration.fallbackAsset)
            val manifest = PdfReflowBuilder.build(directory, "PDF sample & test", "a".repeat(64), "b".repeat(64))
            assertTrue(manifest.blocks.any { it.source.page == 2 })
            val restored = PdfProgressMapper.initial(manifest, null, 2)
            assertNotNull(restored)
            val source = PdfProgressMapper.block(manifest, checkNotNull(restored), null)
            assertEquals(2, source?.source?.page)
            val stored = PdfProgressMapper.serialize(manifest, restored, checkNotNull(source))
            val withoutFragment = restored.copy(locations = restored.locations.copy(fragments = emptyList()))
            assertEquals(source.id, PdfProgressMapper.preferredAnchor(manifest, withoutFragment, stored))
            assertEquals(restored.href, PdfProgressMapper.initial(manifest, stored, 2)?.href)
            val rebuilt = manifest.copy(revision = "e".repeat(64))
            val rebuiltLocator = checkNotNull(PdfProgressMapper.initial(rebuilt, stored, 2))
            assertEquals(source.context, PdfProgressMapper.block(rebuilt, rebuiltLocator, null)?.context)
            val originalStart = checkNotNull(PdfProgressMapper.initial(manifest, stored, 0))
            assertEquals(0, PdfProgressMapper.block(manifest, originalStart, null)?.source?.page)
            ZipFile(File(directory, "book.epub")).use { zip ->
                assertEquals("mimetype", zip.entries().nextElement().name)
                assertTrue(zip.entries().asSequence().any { it.name.endsWith(".png") })
                val contents = zip.entries().asSequence().filter { it.name.startsWith("EPUB/text/") }
                    .joinToString("") { zip.getInputStream(it).bufferedReader().readText() }
                zip.entries().asSequence().filter { it.name.startsWith("EPUB/text/") }.forEach { entry ->
                    val document = org.jsoup.Jsoup.parse(zip.getInputStream(entry).bufferedReader().readText())
                    assertTrue(
                        "Image resources must not eagerly load a group of full-page bitmaps",
                        document.select("img").size <= 1,
                    )
                    document.select("img").forEach {
                        assertTrue(it.attr("width").toInt() > 0)
                        assertTrue(it.attr("height").toInt() > 0)
                    }
                }
                assertTrue(contents.contains("TAIL"))
                assertTrue(contents.contains("body text and words"))
                assertTrue(contents.contains("data-pdf-anchor"))
                assertTrue(contents.contains("#ff0000"))
                assertTrue(contents.contains("<ruby>"))
                assertTrue(contents.contains("<sup>2</sup>"))
                assertTrue(contents.contains("epub:type=\"noteref\""))
                assertTrue(
                    zip.getInputStream(
                        zip.getEntry("EPUB/notes.xhtml"),
                    ).bufferedReader().readText().contains("PDF comment"),
                )
            }
            LocalEpubPublicationService(application).open(
                EpubOpenRequest(
                    1,
                    1,
                    1,
                    "Sample",
                    null,
                    File(directory, "book.epub").toURI().toString(),
                    EpubOpenRequest.OpenSource.LOCAL,
                ),
                null,
            ).useSession { session ->
                assertTrue(session.publication.readingOrder.isNotEmpty())
                assertTrue(session.publication.tableOfContents.isNotEmpty())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cacheClearHonorsActiveLeaseAndEphemeralCopyIsRemovedOnRelease() = runBlocking(Dispatchers.IO) {
        val directory = Files.createTempDirectory(application.cacheDir.toPath(), "pdf-cache-test-").toFile()
        val cache = PdfReflowCacheManager(application, File(directory, "derived"))
        try {
            val file = checkNotNull(UniFile.fromFile(createSample(directory)))
            val artifact = cache.prepare(-987654, 987654, file, "Sample", true) { _, _ -> }
            cache.acquire(artifact)
            cache.clear()
            assertTrue(artifact.epub.isFile)
            cache.release(artifact)
            assertFalse(artifact.directory.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun localCacheHitIsSilentAndStillDetectsChangesWithIdenticalFileMetadata() = runBlocking(Dispatchers.IO) {
        val directory = Files.createTempDirectory(application.cacheDir.toPath(), "pdf-cache-hit-").toFile()
        try {
            val pdf = createSample(directory)
            val input = checkNotNull(UniFile.fromFile(pdf))
            val manager = PdfReflowCacheManager(application, File(directory, "cache"))
            val first = manager.prepare(1, 2, input, "Sample") { _, _ -> }
            var progressCalls = 0
            val second = manager.prepare(1, 2, input, "Sample") { _, _ -> progressCalls++ }
            assertEquals(first.directory, second.directory)
            assertEquals(0, progressCalls)
            assertEquals(first.directory, manager.findPrepared(1, 2, input)?.directory)
            val timestamp = pdf.lastModified()
            val size = pdf.length()
            val changed = pdf.readBytes().toString(Charsets.ISO_8859_1).replace("PDF comment", "NEW comment")
            pdf.writeBytes(changed.toByteArray(Charsets.ISO_8859_1))
            assertTrue(pdf.setLastModified(timestamp))
            assertEquals(size, pdf.length())
            assertEquals(null, manager.findPrepared(1, 2, input))
            val third = manager.prepare(1, 2, input, "Sample") { _, _ -> progressCalls++ }
            assertTrue(third.manifest.revision != first.manifest.revision)
            assertTrue(progressCalls > 0)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun remotePdfUsesProviderRequestAndReusesVersionedCache() = runBlocking(Dispatchers.IO) {
        val directory = Files.createTempDirectory(application.cacheDir.toPath(), "pdf-remote-test-").toFile()
        try {
            val sample = createSample(directory).readBytes()
            val count = java.util.concurrent.atomic.AtomicInteger()
            val adapter = object : koharia.connection.ConnectionRawDownloadAdapter {
                override val rawDownloadClient = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                    assertEquals("fixture", chain.request().header("X-Pdf-Test"))
                    count.incrementAndGet()
                    okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200).message("OK").body(sample.toResponseBody()).build()
                }.build()
                override fun rawFileRequest(resourceUrl: String, rangeStart: Long?) =
                    okhttp3.Request.Builder().url(resourceUrl).header("X-Pdf-Test", "fixture").build()
            }
            val manager = PdfReflowCacheManager(application, File(directory, "cache"))
            val remote = koharia.pdf.cache.PdfRemoteSource(adapter, "https://pdf.test/file", "version1")
            val first = manager.prepare(1, 2, null, "Sample", remote = remote) { _, _ -> }
            val second = manager.prepare(1, 2, null, "Sample", remote = remote) { _, _ -> }
            assertEquals(first.directory, second.directory)
            assertEquals(1, count.get())
            assertFalse(first.directory.resolve("source.pdf").exists())
            manager.prepare(1, 2, null, "Sample", remote = remote.copy(version = "version2")) { _, _ -> }
            assertEquals(2, count.get())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun localPdfReaderDefaultUsesTheOwningRoot() = runBlocking(Dispatchers.IO) {
        val sourceId = 9_000_000_000_000L + System.currentTimeMillis()
        val directory = Files.createTempDirectory(application.cacheDir.toPath(), "pdf-library-route-").toFile()
        val preferences = koharia.source.local.LocalLibraryPreferences(sourceId, Json)
        try {
            createSample(directory)
            val source = koharia.source.local.LocalFolderSource(
                application,
                sourceId,
                "PDF routing test",
                koharia.connection.LibraryConnectionProfile(sourceId, "local-folder", "PDF routing test"),
            )
            val url = koharia.source.local.LocalLibraryLocator.chapterUrl(sourceId, "test-root", "sample.pdf")
            val manga = tachiyomi.domain.manga.model.Manga.create().copy(source = sourceId, url = url)
            val chapter = tachiyomi.domain.chapter.model.Chapter.create().copy(url = url)
            for ((type, expected) in listOf(
                koharia.source.local.LocalLibraryContentType.BOOKS to koharia.connection.LibraryContentScope.BOOK,
                koharia.source.local.LocalLibraryContentType.COMICS to koharia.connection.LibraryContentScope.COMIC,
            )) {
                preferences.setConfig(
                    koharia.source.local.LocalLibraryConfig(
                        roots = listOf(
                            koharia.source.local.LocalLibraryRootConfig(
                                id = "test-root",
                                treeUri = directory.toURI().toString(),
                                contentType = type,
                            ),
                        ),
                        setupCompleted = true,
                    ),
                )
                assertEquals(expected, source.readerContentScope(manga, chapter))
            }
        } finally {
            eu.kanade.tachiyomi.source.sourcePreferences("source_$sourceId").edit().clear().commit()
            directory.deleteRecursively()
        }
    }

    @Test
    fun coldAndCachedPdfOpenInTheNovelReader() = runBlocking(Dispatchers.IO) {
        val sourceId = 8_000_000_000_000L + System.currentTimeMillis()
        val directory = Files.createTempDirectory(application.cacheDir.toPath(), "pdf-novel-start-").toFile()
        val preferences = koharia.source.local.LocalLibraryPreferences(sourceId, Json)
        try {
            createSample(directory)
            preferences.setConfig(
                koharia.source.local.LocalLibraryConfig(
                    roots = listOf(
                        koharia.source.local.LocalLibraryRootConfig(
                            id = "root",
                            treeUri = directory.toURI().toString(),
                            contentType = koharia.source.local.LocalLibraryContentType.BOOKS,
                        ),
                    ),
                    setupCompleted = true,
                ),
            )
            val source = koharia.source.local.LocalFolderSource(
                application,
                sourceId,
                "PDF test",
                koharia.connection.LibraryConnectionProfile(sourceId, "local-folder", "PDF test"),
            )
            val url = koharia.source.local.LocalLibraryLocator.chapterUrl(sourceId, "root", "sample.pdf")
            val manga = tachiyomi.domain.manga.model.Manga.create().copy(id = 8, source = sourceId, url = url)
            val chapter = tachiyomi.domain.chapter.model.Chapter.create().copy(
                id = 9,
                mangaId = 8,
                url = url,
                name = "PDF",
            )
            val cold = koharia.epub.EpubReaderActivity.newPdfReflowIntent(application, 8, 9, sourceId, 1)
            assertEquals(koharia.epub.EpubReaderActivity::class.java.name, cold.component?.className)
            assertTrue(cold.getBooleanExtra(koharia.epub.EpubReaderActivity.EXTRA_PDF_REFLOW_REQUESTED, false))
            val cache = PdfReflowCacheManager(application, File(directory, "derived"))
            val artifact = koharia.epub.service.PdfReflowPublicationService(application, cache = cache)
                .prepare(source, manga, chapter, false)
            assertTrue(artifact.epub.isFile)
            val cached = koharia.epub.EpubReaderActivity.newPdfReflowIntent(
                application,
                8,
                9,
                sourceId,
                1,
                koharia.epub.service.EpubReaderSupportResolution(
                    8,
                    9,
                    sourceId,
                    pdfReflowRevision = artifact.manifest.revision,
                ),
            )
            assertEquals(cold.component, cached.component)
            assertEquals(1, cached.getIntExtra("pdf_reflow_initial_page", -1))
            val legacy = eu.kanade.tachiyomi.ui.reader.ReaderActivity.newIntent(
                application,
                8,
                9,
                sourceId,
                pageIndex = 1,
                autoPdfReflow = true,
            )
            assertEquals(cold.component, legacy.component)
            val original = eu.kanade.tachiyomi.ui.reader.ReaderActivity.newIntent(application, 8, 9, sourceId)
            assertEquals(eu.kanade.tachiyomi.ui.reader.ReaderActivity::class.java.name, original.component?.className)
        } finally {
            eu.kanade.tachiyomi.source.sourcePreferences("source_$sourceId").edit().clear().commit()
            directory.deleteRecursively()
        }
    }

    @Test
    fun progressiveBookShowsInitialChunkAndLoadsDemandedContentBeforeCompletion() = runBlocking(Dispatchers.IO) {
        val input = File(application.filesDir, "pdf-source-sample.pdf")
        assumeTrue("Optional real-book fixture is absent", input.isFile)
        val directory = Files.createTempDirectory(application.cacheDir.toPath(), "pdf-progressive-test-").toFile()
        var session: koharia.epub.session.EpubReaderSession? = null
        var preparation: koharia.pdf.cache.PdfProgressivePublication? = null
        try {
            val cache = PdfReflowCacheManager(application, directory)
            val start = android.os.SystemClock.elapsedRealtime()
            val artifact = cache.prepareProgressive(
                1,
                2,
                checkNotNull(UniFile.fromFile(input)),
                "Sample",
                0,
                false,
                null,
            )
            val initialMs = android.os.SystemClock.elapsedRealtime() - start
            preparation = checkNotNull(artifact.progressive)
            assertFalse(artifact.manifest.isComplete)
            assertEquals(listOf(0, 1, 2, 3), artifact.manifest.availablePages)
            val bootstrap = File(artifact.directory, "publication.epub").readBytes()
            session = LocalEpubPublicationService(application, cache).open(
                EpubOpenRequest(
                    1,
                    2,
                    1,
                    "Sample",
                    null,
                    artifact.epub.toURI().toString(),
                    EpubOpenRequest.OpenSource.LOCAL,
                    pdfReflow = artifact.manifest,
                ),
                null,
            )
            assertEquals(4, artifact.manifest.availablePages.size)
            suspend fun resource(path: String): ByteArray {
                val link = org.readium.r2.shared.publication.Link(
                    href = checkNotNull(org.readium.r2.shared.util.Url(path)),
                )
                val data = checkNotNull(checkNotNull(session).publication.get(link))
                return try {
                    checkNotNull(data.read().getOrNull())
                } finally {
                    data.close()
                }
            }
            val distant = resource("EPUB/text/chunk-35.xhtml")
            assertTrue(distant.decodeToString().contains("id=\"pdf-page-140\""))
            assertEquals(8, artifact.manifest.availablePages.size)
            // This image did not exist in the bootstrap manifest: the live container resolves it on demand.
            val image = resource("EPUB/images/page-7.png")
            assertEquals("PNG", image.copyOfRange(1, 4).decodeToString())
            assertEquals(12, artifact.manifest.availablePages.size)
            preparation.startBackground()
            preparation.awaitPage(280)
            assertTrue(280 in artifact.manifest.availablePages)
            assertFalse(artifact.manifest.isComplete)
            kotlinx.coroutines.withTimeout(90000) { preparation.complete.first { it } }
            assertEquals(296, artifact.manifest.availablePages.size)
            assertTrue(artifact.manifest.isComplete)
            assertTrue(bootstrap.contentEquals(File(artifact.directory, "publication.epub").readBytes()))
            assertTrue(distant.contentEquals(resource("EPUB/text/chunk-35.xhtml")))
            assertFalse(File(artifact.directory, "source.pdf").exists())
            File(application.filesDir, "pdf-progressive-profile.json").writeText(
                org.json.JSONObject().put("firstChunkMs", initialMs)
                    .put("completeMs", android.os.SystemClock.elapsedRealtime() - start)
                    .put("pageCount", 296).put("firstPreparedPages", 4).toString(),
            )
            artifact.epub.copyTo(File(application.filesDir, "pdf-progressive-final.epub"), overwrite = true)
            File(
                application.filesDir,
                "pdf-progressive-final-manifest.json",
            ).writeText(Json.encodeToString(artifact.manifest))
            session.close()
            session = null
            val cached = cache.prepareProgressive(
                1,
                2,
                checkNotNull(UniFile.fromFile(input)),
                "Sample",
                140,
                false,
                null,
            )
            assertTrue(cached.manifest.isComplete)
            assertEquals(artifact.manifest.revision, cached.manifest.revision)
            ZipFile(cached.epub).use { zip ->
                assertNotNull(zip.getEntry("EPUB/images/page-7.png"))
                assertTrue(
                    zip.getInputStream(zip.getEntry("EPUB/text/chunk-35.xhtml")).readBytes().contentEquals(distant),
                )
            }
        } finally {
            session?.close()
            preparation?.stopAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test
    fun progressiveCancellationKeepsCompletedChunksAndProtectsActiveReaders() = runBlocking(Dispatchers.IO) {
        val directory = Files.createTempDirectory(application.cacheDir.toPath(), "pdf-progressive-cancel-").toFile()
        try {
            val input = checkNotNull(UniFile.fromFile(createSample(directory, 20)))
            val cache = PdfReflowCacheManager(application, File(directory, "cache"))
            val initial = cache.prepareProgressive(1, 2, input, "Sample", 12, false, null)
            assertEquals(listOf(12, 13, 14, 15), initial.manifest.availablePages)
            val body = File(initial.directory, "text/chunk-3.xhtml").readBytes()
            cache.acquire(initial)
            cache.clear()
            assertTrue(initial.epub.exists())
            cache.release(initial)
            checkNotNull(initial.progressive).stopAndJoin()
            val resumed = cache.prepareProgressive(1, 2, input, "Sample", 12, false, null)
            assertTrue(body.contentEquals(File(resumed.directory, "text/chunk-3.xhtml").readBytes()))
            assertFalse(resumed.manifest.isComplete)
            cache.clear()
            assertFalse(resumed.directory.exists())
            val ephemeral = cache.prepareProgressive(1, 3, input, "Sample", 0, true, null)
            cache.acquire(ephemeral)
            cache.release(ephemeral)
            kotlinx.coroutines.withTimeout(5000) {
                while (ephemeral.directory.exists()) kotlinx.coroutines.delay(20)
            }
            assertTrue(input.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun progressiveFailurePublishesTerminalStateAndCanResumeAfterRepair() = runBlocking(Dispatchers.IO) {
        val directory = Files.createTempDirectory(application.cacheDir.toPath(), "pdf-progressive-failure-").toFile()
        val cache = PdfReflowCacheManager(application, File(directory, "cache"))
        val held = mutableListOf<koharia.pdf.cache.PdfReflowArtifact>()
        try {
            val input = checkNotNull(UniFile.fromFile(createSample(directory, 20)))
            val artifact = cache.prepareProgressive(1, 2, input, "Sample", 0, false, null)
            cache.acquire(artifact)
            held += artifact
            val worker = checkNotNull(artifact.progressive)
            val firstChunk = File(artifact.directory, "text/chunk-0.xhtml").readBytes()
            val blockedWorkDirectory = File(artifact.directory, "work/1")
            check(blockedWorkDirectory.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()))
            check(!blockedWorkDirectory.exists())
            blockedWorkDirectory.writeText("Test fixture: a file prevents creation of the next chunk directory")
            worker.startBackground()
            val outcome = kotlinx.coroutines.withTimeout(15_000) { worker.outcome.first { it != null } }
            assertTrue(outcome is koharia.pdf.cache.PdfPreparationOutcome.Failed)
            assertTrue(worker.isClosed)
            assertFalse(worker.complete.value)
            worker.awaitPage(0)
            assertTrue(firstChunk.contentEquals(File(artifact.directory, "text/chunk-0.xhtml").readBytes()))
            assertTrue(blockedWorkDirectory.delete())
            cache.release(artifact)
            held.remove(artifact)
            worker.stopAndJoin()
            val resumed = cache.prepareProgressive(1, 2, input, "Sample", 0, false, null)
            cache.acquire(resumed)
            held += resumed
            val resumedWorker = checkNotNull(resumed.progressive)
            resumedWorker.startBackground()
            val completed = kotlinx.coroutines.withTimeout(30_000) { resumedWorker.outcome.first { it != null } }
            assertEquals(koharia.pdf.cache.PdfPreparationOutcome.Completed, completed)
            assertTrue(resumed.manifest.isComplete)
            assertTrue(resumed.epub.isFile)
            assertTrue(input.exists())
        } finally {
            held.forEach { cache.release(it) }
            cache.clear()
            check(directory.canonicalFile.parentFile == application.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }

    @Test
    fun suppliedAutomaticOpenPreservesOriginalPage() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        val mangaId = args.getString("pdf_manga_id")?.toLongOrNull()
        val chapterId = args.getString("pdf_chapter_id")?.toLongOrNull()
        val sourceId = args.getString("pdf_source_id")?.toLongOrNull()
        val page = args.getString("pdf_original_page")?.toIntOrNull()
        assumeTrue(
            "Optional UI validation arguments are absent",
            mangaId != null && chapterId != null && sourceId != null && page != null,
        )
        val intent = eu.kanade.tachiyomi.ui.reader.ReaderActivity.newIntent(
            application,
            mangaId,
            chapterId,
            sourceId,
            pageIndex = page,
            autoPdfReflow = true,
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        application.startActivity(intent)
        val repository = uy.kohesive.injekt.Injekt.get<koharia.epub.session.EpubReaderSessionRepository>()
        val session = kotlinx.coroutines.withTimeout(45000) {
            var session = repository.get(checkNotNull(chapterId))
            while (session?.pdfReflow == null) {
                kotlinx.coroutines.delay(100)
                session = repository.get(chapterId)
            }
            session
        }
        val manifest = checkNotNull(session.pdfReflow)
        val locator = checkNotNull(session.initialLocator)
        assertEquals(page, PdfProgressMapper.block(manifest, locator, null)?.source?.page)
    }

    private inline fun <T> koharia.epub.session.EpubReaderSession.useSession(
        block: (koharia.epub.session.EpubReaderSession) -> T,
    ): T =
        try {
            block(this)
        } finally {
            close()
        }

    private fun createSample(directory: File, pageCount: Int = 3): File {
        val file = File(directory, "sample.pdf")
        fun bytes(text: String) = text.toByteArray(Charsets.ISO_8859_1)
        val objects = mutableListOf<ByteArray>()
        objects += bytes("<< /Type /Catalog /Pages 2 0 R >>")
        val font = 3 + pageCount * 2
        val kids = (0 until pageCount).joinToString(" ") { "${3 + it * 2} 0 R" }
        objects += bytes("<< /Type /Pages /Count $pageCount /Kids [$kids] >>")
        repeat(pageCount) { page ->
            objects += bytes(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 450] " +
                    "/Resources << /Font << /F1 $font 0 R /F2 ${font + 1} 0 R >> " +
                    "/XObject << /Im1 ${font + 2} 0 R >> >> " +
                    "/Contents ${4 + page * 2} 0 R ${if (page == 0) "/Annots [${font + 3} 0 R]" else ""} >>",
            )
            val content = if (page == 1) {
                "q 120 0 0 120 60 200 cm /Im1 Do Q"
            } else {
                buildString {
                    append("BT /F2 24 Tf 1 0 0 rg 20 400 Td (Chapter ${page + 1}) Tj ET\n")
                    repeat(12) { line ->
                        val text = if (page == 2 && line == 11) {
                            "TAIL of the original document."
                        } else {
                            "Line $line with body text and words."
                        }
                        append("BT /F1 12 Tf 0 0 0 rg 20 ${370 - line * 22} Td ($text) Tj ET\n")
                    }
                    if (page == 0) {
                        append("BT /F1 6 Tf 0 0 0 rg 20 381 Td (fu) Tj ET\n")
                        append("BT /F1 12 Tf 20 80 Td (x) Tj ET\n")
                        append("BT /F1 6 Tf 27 86 Td (2) Tj ET\n")
                    }
                }
            }
            objects += bytes("<< /Length ${bytes(content).size} >>\nstream\n$content\nendstream")
        }
        objects += bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        objects += bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>")
        objects += bytes(
            "<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB " +
                "/BitsPerComponent 8 /Length 3 >>\nstream\n",
        ) + byteArrayOf(0, -1, -1) + bytes("\nendstream")
        objects += bytes("<< /Type /Annot /Subtype /Text /Rect [20 360 30 375] /Contents (PDF comment) >>")
        val output = ByteArrayOutputStream()
        output.write(bytes("%PDF-1.4\n"))
        val offsets = objects.mapIndexed { i, obj ->
            output.size().also {
                output.write(bytes("${i + 1} 0 obj\n"))
                output.write(obj)
                output.write(bytes("\nendobj\n"))
            }
        }
        val xref = output.size()
        output.write(bytes("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n"))
        offsets.forEach { output.write(bytes(it.toString().padStart(10, '0') + " 00000 n \n")) }
        output.write(bytes("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF"))
        file.writeBytes(output.toByteArray())
        return file
    }
}

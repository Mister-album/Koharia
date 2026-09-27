package koharia.kavita

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Requires an explicitly authorized writable test account; never targets the public demo. */
class KavitaLiveTest {
    @Test fun publisherStylesAndEmbeddedResourcesUseRealServerFiles() = runBlocking {
        api().use { api ->
            val catalog = KavitaCatalog(1, "resources-live", MemoryKavitaRepository(), api) {}
            var styles = 0
            var images = 0
            for (series in catalog.page(1, KavitaFilter(listOf(KavitaFilterStatement(21, 0, "3")))).items.take(5)) {
                val chapter = catalog.volumes(series.id).first { it.chapters.isNotEmpty() }.chapters.first()
                for (page in 0 until minOf(2, chapter.pages)) {
                    val raw = api.client.newCall(
                        api.request("Book/${chapter.id}/book-page", "page" to page),
                    ).execute().use {
                        it.body.string()
                    }
                    val doc = Jsoup.parse(rewriteKavitaHtml(raw, api, chapter.id))
                    val urls = doc.select("img[src], image[xlink:href], image[href]").map {
                        it.attr("src").ifBlank { it.attr("xlink:href").ifBlank { it.attr("href") } }
                    } +
                        doc.select("style").flatMap {
                            kavitaCssUrls.findAll(it.data()).map { it.groupValues[2] }.toList()
                        }
                    for (url in urls.distinct().filter { it.startsWith(api.virtualBase.toString()) }) {
                        val resource =
                            fetchKavitaEpubResource(api, chapter.id, requireNotNull(api.virtualBase.resolve(url)))
                        assertTrue(resource.bytes.isNotEmpty())
                        if (resource.mediaType == "text/css") {
                            styles++
                            val css =
                                rewriteKavitaCss(resource.bytes.toString(Charsets.UTF_8), api, chapter.id, resource.url)
                            assertFalse(css.contains("apiKey="))
                            for (dependency in kavitaCssUrls.findAll(css).map { it.groupValues[2] }.distinct()) {
                                if (!dependency.startsWith(api.virtualBase.toString())) continue
                                val extension = dependency.substringAfterLast('.').lowercase()
                                // This fixture references fonts that the server reports missing; layout must still render.
                                if (extension in setOf("ttf", "otf", "woff", "woff2")) continue
                                assertTrue(
                                    fetchKavitaEpubResource(
                                        api,
                                        chapter.id,
                                        requireNotNull(api.virtualBase.resolve(dependency)),
                                    ).bytes.isNotEmpty(),
                                )
                            }
                        } else if (resource.mediaType.startsWith("image/")) {
                            images++
                        }
                    }
                }
            }
            assertTrue(styles > 0 && images > 0)
            println("Verified publisher styles: $styles; images: $images")
        }
    }

    @Test fun remoteHistoryUsesRealSessionTimesAndDoesNotWriteProgress() = runBlocking {
        api().use { api ->
            val catalog = KavitaCatalog(1, "history-live", MemoryKavitaRepository(), api) {}
            val history = catalog.history()
            assertTrue(history.all { it.readAt > 0 && it.chapterId > 0 && it.seriesId > 0 })
            assertEquals(history.size, history.map { it.chapterId }.distinct().size)
            val before = requests.get()
            assertEquals(history, catalog.history())
            assertEquals(before, requests.get())
            println("Remote history chapters: ${history.size}")
        }
    }

    @Test fun personalRatingReviewAndSharingPreserveExistingPreferences() = runBlocking {
        api().use { api ->
            val catalog = KavitaCatalog(1, "review-live", MemoryKavitaRepository(), api) {}
            val organization = KavitaOrganization(1, catalog) {}
            val seriesId = sample(api, 3).first.seriesId
            val user = api.getAccount()
            val originalRating = api.get<kotlinx.serialization.json.JsonObject>("Series/$seriesId")
                .get("userRating")?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
            fun isSeriesReview(value: kotlinx.serialization.json.JsonObject) =
                value.longValue("seriesId") == seriesId && value["chapterId"]?.toString() in listOf(null, "null", "0")
            val originalReview = api.get<List<kotlinx.serialization.json.JsonObject>>("Review/all?userId=${user.id}")
                .firstOrNull(::isSeriesReview)
            val preferences = api.get<kotlinx.serialization.json.JsonObject>("Users/get-preferences")
            val social = preferences["socialPreferences"]!!.jsonObject
            val share = social["shareAnnotations"].toString() == "true"
            val body = "Koharia review " + java.util.UUID.randomUUID()
            try {
                organization.rate(seriesId, 3.5f)
                organization.review(seriesId, body)
                assertEquals(
                    body,
                    api.get<List<kotlinx.serialization.json.JsonObject>>("Review/all?userId=${user.id}")
                        .single(::isSeriesReview).textValue("body"),
                )
                organization.deleteReview(seriesId)
                organization.annotationSharing("shareAnnotations", share)
                val after = api.get<kotlinx.serialization.json.JsonObject>("Users/get-preferences")
                assertEquals(social, after["socialPreferences"])
                assertEquals(preferences["bookReaderHighlightSlots"], after["bookReaderHighlightSlots"])
                assertEquals(preferences["bookReaderFontSize"], after["bookReaderFontSize"])
                assertTrue(kavitaHighlightColors(after).isNotEmpty())
            } finally {
                if (originalReview == null) {
                    organization.deleteReview(seriesId)
                } else {
                    organization.review(seriesId, originalReview.textValue("body"))
                }
                organization.rate(seriesId, originalRating)
            }
        }
    }

    @Test fun fullLibrarySearchSmartFilterAndDashboardRoundTrip() = runBlocking {
        api().use { api ->
            val catalog = KavitaCatalog(1, "dashboard-live", MemoryKavitaRepository(), api) {}
            val organization = KavitaOrganization(1, catalog) {}
            val libraries = catalog.libraries()
            for (library in libraries) {
                val first = api.seriesPage(1, kavitaShelfFilter(listOf(library.id), "", "1 asc")).items.firstOrNull()
                    ?: continue
                val filter = kavitaShelfFilter(libraries.map { it.id }, first.name, "1 asc")
                assertTrue(api.seriesPage(1, filter).items.any { it.id == first.id })
                assertTrue(
                    api.seriesPage(
                        1,
                        kavitaShelfFilter(
                            libraries.map { it.id },
                            "Koharia nonexistent " + java.util.UUID.randomUUID(),
                            "1 asc",
                        ),
                    ).items.isEmpty(),
                )
            }
            val name = "Koharia filter " + java.util.UUID.randomUUID()
            var filterId: Long? = null
            var streamId: Long? = null
            try {
                organization.saveFilter(0, name, kavitaShelfFilter(libraries.map { it.id }, "", "1 asc"))
                filterId = api.get<List<kotlinx.serialization.json.JsonObject>>("Filter")
                    .single { it.textValue("name") == name }.longValue("id")
                organization.addDashboardFilter(filterId)
                streamId = api.get<List<kotlinx.serialization.json.JsonObject>>("Stream/dashboard?visibleOnly=false")
                    .single { it.longValue("smartFilterId") == filterId }.longValue("id")
                organization.renameFilter(filterId, "$name renamed")
                assertEquals(
                    "$name renamed",
                    api.get<List<kotlinx.serialization.json.JsonObject>>("Filter")
                        .single { it.longValue("id") == filterId }.textValue("name"),
                )
                organization.updateDashboard(streamId, visible = false)
                assertEquals(
                    "false",
                    api.get<List<kotlinx.serialization.json.JsonObject>>("Stream/dashboard?visibleOnly=false").single {
                        it.longValue("id") == streamId
                    }["visible"].toString(),
                )
                organization.updateDashboard(streamId, delta = -1)
                organization.updateDashboard(streamId, delta = 1)
                organization.updateDashboard(streamId, visible = true)
                organization.updateDashboard(streamId, remove = true)
                streamId = null
                organization.deleteFilter(filterId)
                filterId = null
            } finally {
                // A lost create response may still have created the UUID-named test filter.
                val filters = api.get<List<kotlinx.serialization.json.JsonObject>>("Filter")
                    .filter { it.textValue("name") in listOf(name, "$name renamed") }.map { it.longValue("id") }
                val streams = api.get<List<kotlinx.serialization.json.JsonObject>>("Stream/dashboard?visibleOnly=false")
                    .filter { it.longValue("smartFilterId") in filters || it.longValue("id") == streamId }
                for (stream in streams) organization.updateDashboard(stream.longValue("id"), remove = true)
                for (id in (filters + listOfNotNull(filterId)).distinct()) organization.deleteFilter(id)
            }
        }
    }

    @Test fun imageBookmarksAndPersonalTocSurviveOfflineRestart() = runBlocking {
        api().use { api ->
            val repository = MemoryKavitaRepository()
            val catalog = KavitaCatalog(1, "bookmark-live", repository, api) {}
            val stopped = CoroutineScope(Dispatchers.IO + SupervisorJob().apply { cancel() })
            fun coordinator() = KavitaBookmarks(1, "bookmark-live", repository, catalog, stopped, {}, { true })
            val (comic, chapter) = sample(api, 1)
            val (epub, book) = sample(api, 3)
            val existing = api.get<List<kotlinx.serialization.json.JsonObject>>(
                "Reader/chapter-bookmarks?chapterId=${comic.chapterId}",
            )
            val page = (0 until chapter.pages).first { number ->
                existing.none { it.longValue("page") == number.toLong() }
            }
            val actions = mutableListOf(KavitaBookmarkState(comic, KavitaBookmarkKind.IMAGE, page))
            val title = "Koharia bookmark " + java.util.UUID.randomUUID()
            var image: KavitaBookmarkState? = null
            for (index in 0 until minOf(book.pages, 24)) {
                val html = api.client.newCall(api.request("Book/${epub.chapterId}/book-page", "page" to index))
                    .execute().use {
                        KavitaApiClient.checkResponse(it)
                        Jsoup.parse(rewriteKavitaHtml(it.body.string(), api, epub.chapterId))
                    }
                val node = html.select("[data-koharia-kavita-path]").firstOrNull { it.ownText().length > 15 }
                if (node != null && actions.none { it.kind == KavitaBookmarkKind.TOC }) {
                    actions += KavitaBookmarkState(
                        epub,
                        KavitaBookmarkKind.TOC,
                        index,
                        title = title,
                        anchor = node.attr("data-koharia-kavita-path"),
                        selectedText = node.text().take(20),
                    )
                }
                if (image == null) {
                    val bookmarks = api.get<List<kotlinx.serialization.json.JsonObject>>(
                        "Reader/chapter-bookmarks?chapterId=${epub.chapterId}",
                    )
                    image = html.select("[data-koharia-kavita-image-index]").map { element ->
                        KavitaBookmarkState(
                            epub,
                            KavitaBookmarkKind.IMAGE,
                            index,
                            imageOffset = element.attr("data-koharia-kavita-image-index").toInt(),
                            anchor = element.attr("data-koharia-kavita-path"),
                        )
                    }.firstOrNull { candidate -> bookmarks.none(candidate::matches) }
                }
                if (image != null && actions.any { it.kind == KavitaBookmarkKind.TOC }) break
            }
            actions += requireNotNull(image)
            assertTrue(actions.any { it.kind == KavitaBookmarkKind.TOC })
            try {
                val first = coordinator()
                for (action in actions) first.entries(action.ref, action.kind)
                offline.set(true)
                for (action in actions) first.set(action)
                val restarted = coordinator()
                for (action in actions) assertTrue(restarted.entries(action.ref, action.kind).any(action::matches))
                offline.set(false)
                restarted.flush()
                assertTrue(repository.operations(1, "bookmark-live").all { !it.pending })
                for (action in actions) {
                    assertTrue(api.get<List<kotlinx.serialization.json.JsonObject>>(action.path).any(action::matches))
                    restarted.set(action.copy(desired = false))
                }
                restarted.flush()
                for (action in actions) {
                    assertFalse(api.get<List<kotlinx.serialization.json.JsonObject>>(action.path).any(action::matches))
                }
            } finally {
                offline.set(false)
                val cleanup = coordinator()
                for (action in actions) cleanup.set(action.copy(desired = false))
                cleanup.flush()
            }
        }
    }

    @Test fun personalStatsReadingProfilesAndSubscriptionGate() = runBlocking {
        api().use { api ->
            val catalog = KavitaCatalog(1, "personal-live", MemoryKavitaRepository(), api) {}
            val user = api.getAccount()
            val stats = catalog.resource("Stats/user-stats?userId=${user.id}").jsonObject
            assertTrue(stats.keys.containsAll(listOf("booksRead", "comicsRead", "pagesRead", "wordsRead")))
            val (ref) = sample(api, 3)
            val profile = catalog.resource("reading-profile/${ref.libraryId}/${ref.seriesId}?skipImplicit=false")
                .jsonObject
            assertFalse(KavitaProfileImport.from(profile).isEmpty)
            val subscribed = api.get<Boolean>("License/valid-license")
            println("Kavita Plus license available: $subscribed")
            if (subscribed) {
                val library = catalog.libraries().single { it.id == ref.libraryId }
                val extra = catalog.resource(
                    "Metadata/series-detail-plus?seriesId=${ref.seriesId}&libraryType=${library.type}",
                ).jsonObject
                assertTrue(extra.containsKey("recommendations"))
                val safe = api.json.encodeToString(catalog.scrobbleStatuses())
                assertFalse(safe.contains("authenticationToken"))
                assertFalse(safe.contains("refreshToken"))
            }
            val before = requests.get()
            catalog.resource("Stats/user-stats?userId=${user.id}")
            catalog.resource("reading-profile/${ref.libraryId}/${ref.seriesId}?skipImplicit=false")
            assertEquals(before, requests.get())
        }
    }

    @Test fun annotationCreateOfflineEditAndDeleteRoundTrip() = runBlocking {
        api().use { api ->
            val (ref, chapter) = sample(api, 3)
            val user = api.getAccount()
            val note = "Koharia annotation " + java.util.UUID.randomUUID()
            val repository = MemoryKavitaRepository()
            val stopped = CoroutineScope(Dispatchers.IO + SupervisorJob().apply { cancel() })
            val catalog = KavitaCatalog(1, "annotation-live", repository, api) {}
            fun coordinator() = KavitaAnnotationCoordinator(
                1,
                "annotation-live",
                user.id,
                repository,
                catalog,
                stopped,
                {},
                { true },
            )
            try {
                var selected: org.jsoup.nodes.Element? = null
                var page = 0
                for (index in 0 until minOf(chapter.pages, 12)) {
                    val raw = api.client.newCall(api.request("Book/${ref.chapterId}/book-page", "page" to index))
                        .execute().use {
                            KavitaApiClient.checkResponse(it)
                            it.body.string()
                        }
                    val html = Jsoup.parse(rewriteKavitaHtml(raw, api, ref.chapterId))
                    selected = html.select("p[data-koharia-kavita-path]").firstOrNull {
                        it.text().length > 30 && it.select("app-epub-highlight").isEmpty()
                    }
                    if (selected != null) {
                        page = index
                        break
                    }
                }
                val element = requireNotNull(selected)
                val path = element.attr("data-koharia-kavita-path")
                val draft = KavitaAnnotation(
                    chapterId = ref.chapterId, seriesId = ref.seriesId, volumeId = ref.volumeId,
                    libraryId = ref.libraryId, ownerUserId = user.id,
                    xPath = path, endingXPath = path, selectedText = element.text().take(30), pageNumber = page,
                ).withPlainComment(note)
                val first = coordinator()
                val key = first.create(draft, null)
                first.flush()
                val created = first.cached(ref.chapterId).single()
                assertFalse(created.entry.pending)
                assertTrue(requireNotNull(created.entry.remoteId) > 0)
                val delta = api.json.parseToJsonElement(created.state.annotation.comment).jsonObject
                assertTrue(delta.containsKey("ops"))
                val browserFilter = kotlinx.serialization.json.buildJsonObject {
                    put("entityType", kotlinx.serialization.json.JsonPrimitive(3))
                    put("combination", kotlinx.serialization.json.JsonPrimitive(1))
                    put(
                        "statements",
                        api.json.parseToJsonElement(
                            api.json.encodeToString(
                                listOf(
                                    KavitaFilterStatement(6, 7, note),
                                ),
                            ),
                        ),
                    )
                }
                val browsed = catalog.annotationPage(1, browserFilter)
                assertTrue(browsed.items.any { it.id == created.entry.remoteId })
                val beforeBrowse = requests.get()
                catalog.annotationPage(1, browserFilter)
                assertEquals(beforeBrowse, requests.get())
                val rendered = api.client.newCall(api.request("Book/${ref.chapterId}/book-page", "page" to page))
                    .execute().use {
                        KavitaApiClient.checkResponse(it)
                        it.body.string()
                    }
                assertTrue(rendered.contains("epub-highlight-${created.entry.remoteId}"))
                offline.set(true)
                first.edit(key, created.entry.revision, "$note edited\nsecond line", 2, true)
                assertTrue(first.cached(ref.chapterId).single().entry.pending)
                val restarted = coordinator()
                assertTrue(restarted.cached(ref.chapterId).single().state.annotation.containsSpoiler)
                offline.set(false)
                restarted.flush()
                val updated = restarted.cached(ref.chapterId).single()
                assertFalse(updated.entry.pending)
                assertEquals(2, updated.state.annotation.selectedSlotIndex)
                assertTrue(updated.state.annotation.commentPlainText.contains("$note edited"))
                restarted.delete(key, updated.entry.revision)
                restarted.flush()
                assertTrue(restarted.cached(ref.chapterId).isEmpty())
            } finally {
                offline.set(false)
                val remaining = api.get<List<KavitaAnnotation>>("Annotation/all?chapterId=${ref.chapterId}")
                    .filter { it.commentPlainText.startsWith(note) }
                for (annotation in remaining) api.mutate("Annotation?annotationId=${annotation.id}", method = "DELETE")
                assertTrue(
                    api.get<List<KavitaAnnotation>>("Annotation/all?chapterId=${ref.chapterId}")
                        .none { it.commentPlainText.startsWith(note) },
                )
            }
        }
    }

    @Test fun personalCollectionsAndCrossSeriesReadingListMutations() = runBlocking {
        api().use { api ->
            val catalog = KavitaCatalog(1, "live-organization", MemoryKavitaRepository(), api) {}
            val organization = KavitaOrganization(1, catalog) {}
            val samples = listOf(1, 3, 4).map { sample(api, it).first }
            val title = "Koharia integration " + java.util.UUID.randomUUID()
            var collectionId: Long? = null
            var listId: Long? = null
            try {
                organization.addCollection(samples.first().seriesId, title = title)
                collectionId = api.execute("Collection").let {
                    (it as kotlinx.serialization.json.JsonArray).map { value -> value.jsonObject }
                        .single { value -> value.textValue("title") == title }.longValue("id")
                }
                organization.editCollection(collectionId, "$title edited", "temporary integration fixture")
                assertEquals("$title edited", organization.collection(collectionId, true).textValue("title"))
                organization.addCollection(samples[1].seriesId, collectionId)
                organization.removeCollectionMember(collectionId, samples[1].seriesId)

                organization.createList(title)
                listId = api.allReadingLists().single { it.title == title }.id
                for (ref in samples) organization.addListMember(listId, ref.seriesId, ref.chapterId)
                fun decodeItems(text: String) = api.decode<List<KavitaListItem>>(text).sortedBy { it.order }
                val items = decodeItems(api.execute("ReadingList/items?readingListId=$listId").toString())
                assertEquals(samples.map { it.chapterId }, items.map { it.chapterId })
                organization.moveListMember(listId, items.last().id, -1)
                val moved = decodeItems(api.execute("ReadingList/items?readingListId=$listId").toString())
                assertEquals(
                    listOf(items[0].chapterId, items[2].chapterId, items[1].chapterId),
                    moved.map {
                        it.chapterId
                    },
                )
                organization.editList(listId, "$title edited", "cross-series queue")
                assertEquals("cross-series queue", organization.readingList(listId, true).textValue("summary"))
                val cbl = KavitaCbl(catalog) {}
                val exported = cbl.export(listId)
                val document = Jsoup.parse(exported.toString(Charsets.UTF_8), "", org.jsoup.parser.Parser.xmlParser())
                requireNotNull(document.selectFirst("ReadingList > Name")).text("$title imported")
                val uploaded = cbl.upload(document.outerHtml().toByteArray(), false)
                assertEquals(3, uploaded.summary.items.size)
                val decisions = uploaded.summary.items.mapIndexed { index, row ->
                    val ref = samples.single { it.chapterId == moved[index].chapterId }
                    row.order to KavitaCblDecision(ref.seriesId, ref.volumeId, ref.chapterId)
                }.toMap()
                val imported = cbl.finish(uploaded, decisions)
                assertTrue(imported.readingListId > 0)
                val importedItems =
                    decodeItems(api.execute("ReadingList/items?readingListId=${imported.readingListId}").toString())
                assertEquals(moved.map { it.chapterId }, importedItems.map { it.chapterId })
                organization.moveListMember(listId, items[0].id, 0)
                assertEquals(2, decodeItems(api.execute("ReadingList/items?readingListId=$listId").toString()).size)
            } finally {
                // Recover IDs even when a creation response was lost; never delete pre-existing user objects.
                val lists = api.allReadingLists().filter {
                    it.title in listOf(title, "$title edited", "$title imported")
                }
                for (list in lists) organization.deleteList(list.id)
                val collections = api.execute("Collection") as kotlinx.serialization.json.JsonArray
                for (value in collections.map { it.jsonObject }.filter {
                    it.textValue("title") in listOf(title, "$title edited")
                }) {
                    organization.deleteCollection(value.longValue("id"))
                }
                assertTrue(api.allReadingLists().none { it.id == listId })
                assertTrue(
                    (api.execute("Collection") as kotlinx.serialization.json.JsonArray)
                        .none { it.jsonObject.longValue("id") == collectionId },
                )
            }
        }
    }

    private val offline = AtomicBoolean(false)
    private val requests = AtomicInteger()
    private fun api(): KavitaApiClient {
        assumeTrue(System.getenv("KAVITA_LIVE_WRITE") == "true")
        val credentials = OkHttpClient().newCall(
            Request.Builder().url(requireNotNull(System.getenv("KAVITA_LIVE_BRIDGE"))).build(),
        ).execute().use { kotlinx.serialization.json.Json.parseToJsonElement(it.body.string()).jsonObject }
        val address = credentials.getValue("server").jsonPrimitive.content
        require(!address.contains("demo.kavitareader.com", ignoreCase = true))
        val network = OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).addInterceptor {
            requests.incrementAndGet()
            if (offline.get()) throw IOException("Test transport disconnected")
            it.proceed(it.request()).also { response ->
                if (response.code >= 400) {
                    // Paths only: query strings and headers can contain credentials.
                    println(
                        "Kavita fixture HTTP ${response.code}: ${it.request().method} ${it.request().url.encodedPath}",
                    )
                    val diagnostic = response.peekBody(32 * 1024).string()
                    val types = Regex("""(?:[A-Za-z_]\w*\.)*[A-Z]\w*(?:Exception|Error)""")
                        .findAll(diagnostic).map { match -> match.value }.distinct().toList()
                    println("Kavita fixture error types: $types")
                    for (known in listOf(
                        "SQLite Error 19",
                        "FOREIGN KEY constraint failed",
                        "UNIQUE constraint failed",
                        "NOT NULL constraint failed",
                    )) {
                        if (diagnostic.contains(known)) println("Kavita fixture diagnostic: $known")
                    }
                }
            }
        }.build()
        return KavitaApiClient(network, address, credentials.getValue("key").jsonPrimitive.content, "live-test")
    }

    private suspend fun sample(api: KavitaApiClient, format: Int): Pair<KavitaChapterRef, KavitaChapter> {
        val filter = KavitaFilter(listOf(KavitaFilterStatement(21, 0, format.toString())))
        var page = 1
        do {
            val result = api.seriesPage(page++, filter)
            for (series in result.items) {
                for (volume in api.volumes(series.id)) {
                    val chapter = volume.chapters.firstOrNull { it.pages > 4 } ?: continue
                    return KavitaChapterRef(series.libraryId, series.id, volume.id, chapter.id, format) to chapter
                }
            }
        } while (result.hasNext)
        error("Test library needs a sample of format $format with at least five content pages")
    }

    @Test fun realCatalogWarmStartEmptyCacheAndRefreshFailure() = runBlocking {
        api().use { api ->
            val repository = MemoryKavitaRepository()
            val catalog = KavitaCatalog(1, "live", repository, api) {}
            assertTrue(catalog.libraries().isNotEmpty())
            val filter = KavitaFilter()
            val first = catalog.page(1, filter)
            assertTrue(first.items.isNotEmpty())
            val emptyFilter = KavitaFilter(
                listOf(
                    KavitaFilterStatement(
                        1,
                        0,
                        "koharia-absent-" + java.util.UUID.randomUUID(),
                    ),
                ),
            )
            assertTrue(catalog.page(1, emptyFilter).items.isEmpty())
            val before = requests.get()
            offline.set(true)
            val restarted = KavitaCatalog(1, "live", repository, api) {}
            assertEquals(first, restarted.page(1, filter))
            assertTrue(restarted.page(1, emptyFilter).items.isEmpty())
            assertTrue(restarted.libraries().isNotEmpty())
            assertEquals(before, requests.get())
            assertTrue(runCatching { restarted.refresh(filter) }.isFailure)
            assertEquals(first, restarted.page(1, filter))
            val otherAccount = KavitaCatalog(1, "other-account", repository, api) {}
            assertTrue(runCatching { otherAccount.page(1, filter) }.isFailure)
        }
    }

    @Test fun comicServerPagesAndPdfFile() = runBlocking {
        api().use { api ->
            assertTrue(api.capabilities(api.getAccount()).downloads)
            for (format in listOf(1, 4)) {
                val (ref, chapter) = sample(api, format)
                api.client.newCall(Request.Builder().url(api.page(ref.chapterId, 0)).build()).execute().use {
                    assertEquals(200, it.code)
                    assertTrue(it.body.contentType().toString().startsWith("image/"))
                    assertTrue(it.body.bytes().isNotEmpty())
                }
                assertTrue(chapter.files.isNotEmpty())
                if (format == 4 && chapter.files.size == 1) {
                    for (request in listOf(
                        api.request("Reader/pdf", "chapterId" to ref.chapterId),
                        api.rawRequest(ref.chapterId),
                    )) {
                        api.client.newCall(request).execute().use {
                            assertEquals(200, it.code)
                            assertEquals("%PDF-", it.body.source().readUtf8(5))
                        }
                    }
                }
            }
        }
    }

    @Test fun realEpubExportsAllContentPagesWithReversibleAnchors() = runBlocking {
        api().use { api ->
            val (ref, _) = sample(api, 3)
            val catalog = KavitaCatalog(1, "live", MemoryKavitaRepository(), api) {}
            val info = catalog.book(ref.chapterId)
            val target = File(requireNotNull(System.getenv("KAVITA_LIVE_ARTIFACTS")), "temporary-publication.epub")
            try {
                KavitaOfflineEpub(api, catalog).write(ref.chapterId, target) {}
                ZipFile(target).use { zip ->
                    val pages = zip.entries().asSequence().filter {
                        it.name.matches(Regex("OEBPS/koharia-epub/page-[0-9]+\\.html"))
                    }.toList()
                    assertEquals(info.pages, pages.size)
                    val parser = DocumentBuilderFactory.newInstance().apply {
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    }.newDocumentBuilder()
                    for (page in pages) {
                        val bytes = zip.getInputStream(page).use { it.readBytes() }
                        parser.parse(bytes.inputStream())
                        val html = bytes.toString(Charsets.UTF_8)
                        assertFalse(html.contains("apiKey=", ignoreCase = true))
                        assertFalse(html.contains("kavita.invalid"))
                        assertTrue(Jsoup.parse(html).select("[data-koharia-kavita-path]").isNotEmpty())
                    }
                }
                // The exported publication is usable with no remaining live transport.
                offline.set(true)
                ZipFile(target).use { assertTrue(it.getEntry("OEBPS/content.opf") != null) }
            } finally {
                target.delete()
            }
        }
    }

    @Test fun progressOfflineResumeConflictUnreadAndSignalR() = runBlocking {
        api().use { api ->
            val events = CopyOnWriteArrayList<KavitaEvent>()
            val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val stream = KavitaEvents(api, eventScope) { events.addAll(it) }
            val eventJob = eventScope.launch { stream.run() }
            try {
                withTimeout(20_000) { stream.connected.first { it } }
                for (format in listOf(1, 3, 4)) {
                    val (ref, chapter) = sample(api, format)
                    val original = api.progress(ref.chapterId).copy(
                        libraryId = ref.libraryId,
                        seriesId = ref.seriesId,
                        volumeId = ref.volumeId,
                        chapterId = ref.chapterId,
                    )
                    val repository = MemoryKavitaRepository()
                    // Disable automatic retry; each transport transition is controlled by the test.
                    val scope = CoroutineScope(SupervisorJob()).apply { cancel() }
                    fun coordinator() = KavitaReadingCoordinator(1, "live", repository, api, scope, {}, {})
                    try {
                        val first = coordinator()
                        first.pull(ref, chapter.pages)
                        val anchor = if (format == 3) "//body/p[1]" else null
                        val before = requests.get()
                        first.record(ref, chapter.pages, chapter.pages, System.currentTimeMillis(), initial = true)
                        assertFalse(first.hasPending(ref.chapterId))
                        assertEquals(before, requests.get())
                        offline.set(true)
                        first.record(ref, 2, chapter.pages, System.currentTimeMillis(), anchor = anchor)
                        assertTrue(first.hasPending(ref.chapterId))
                        assertTrue(runCatching { first.flush() }.isFailure)
                        val resumed = coordinator()
                        assertEquals(2, resumed.cached(ref.chapterId)?.progress?.pageNum)
                        offline.set(false)
                        withTimeout(20_000) { stream.connected.first { it } }
                        resumed.flush()
                        assertFalse(resumed.hasPending(ref.chapterId))
                        assertTrue(
                            sameKavitaPosition(
                                KavitaProgress(pageNum = 2, bookScrollId = anchor),
                                api.progress(ref.chapterId),
                            ),
                        )

                        // A second client advances after our baseline while this client reads offline.
                        resumed.record(ref, 3, chapter.pages, System.currentTimeMillis(), anchor = anchor)
                        api.saveProgress(original.copy(pageNum = 4, bookScrollId = anchor))
                        resumed.flush()
                        assertTrue(resumed.hasPending(ref.chapterId))
                        assertTrue(resumed.cached(ref.chapterId)?.conflict == true)
                        assertEquals(4, api.progress(ref.chapterId).pageNum)
                        resumed.pull(ref, chapter.pages)
                        resumed.record(
                            ref,
                            0,
                            chapter.pages,
                            System.currentTimeMillis(),
                            explicit = true,
                            unread = true,
                        )
                        resumed.flush()
                        assertEquals(0, api.progress(ref.chapterId).pageNum)
                        assertFalse(resumed.hasPending(ref.chapterId))
                        resumed.record(ref, chapter.pages, chapter.pages, System.currentTimeMillis(), explicit = true)
                        resumed.flush()
                        assertEquals(chapter.pages, api.progress(ref.chapterId).pageNum)
                        withTimeout(20_000) {
                            while (events.none {
                                    it.name == "UserProgressUpdate" &&
                                        it.body["seriesId"]?.jsonPrimitive?.longOrNull == ref.seriesId
                                }
                            ) {
                                delay(100)
                            }
                        }
                    } finally {
                        offline.set(false)
                        api.saveProgress(original)
                        assertTrue(
                            sameKavitaPosition(original, api.progress(ref.chapterId)),
                            "Original reading position must be restored",
                        )
                    }
                }
            } finally {
                eventJob.cancel()
                eventScope.cancel()
            }
        }
    }
}

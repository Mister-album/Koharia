package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupEpubBookmark
import eu.kanade.tachiyomi.data.backup.models.BackupEpubProgress
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BackupTtsProgress
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.providers.BackupKavitaAnnotation
import eu.kanade.tachiyomi.data.backup.providers.BackupKavitaState
import eu.kanade.tachiyomi.data.backup.providers.BackupLanraragiState
import eu.kanade.tachiyomi.data.backup.providers.BackupSmangaState
import eu.kanade.tachiyomi.data.backup.providers.BackupSuwayomiState
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryRootConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BackupConnectionReferencesTest {
    private val mapper = BackupConnectionReferences(17, 91)

    @Test
    fun `provider identity remap retains account and remote ids including tank routes`() {
        for (path in listOf(
            "/lanraragi/17/archive/same",
            "/lanraragi/17/tank/same",
            "/kavita/17/account/chapter/1/2/3/17.epub",
            "/smanga/17/account/chapter/1/2/17.pages",
            "/suwayomi/17/account/chapter/3/17",
        )) {
            assertEquals(path.replaceFirst("/17/", "/91/"), mapper.url(path))
        }
    }

    @Test
    fun `local URI encoding is retained and network document owner alone changes`() {
        for (scheme in listOf("koharia-local-v1", "koharia-local-v2", "koharia-incoming-v1")) {
            assertEquals("$scheme://91/root/a%20b%2F17#anchor", mapper.url("$scheme://17/root/a%20b%2F17#anchor"))
        }
        assertEquals(
            "content://app.fixture.library-storage/document/91%7Caccount%7Ca%2Bb%2F17",
            mapper.url("content://app.fixture.library-storage/document/17%7Caccount%7Ca%2Bb%2F17"),
        )
        assertEquals("content://saf/tree/17", mapper.url("content://saf/tree/17"))
    }

    @Test
    fun `virtual images and namespace queries cannot keep old connection owner`() {
        assertEquals(
            "https://kavita.invalid/91-account/api/Image/17",
            mapper.url("https://kavita.invalid/17-account/api/Image/17"),
        )
        assertEquals(
            "https://server/image?kohariaScope=91%2Faccount",
            mapper.url("https://server/image?kohariaScope=17%2Faccount"),
        )
        assertEquals("https://server/opds?koharia=91-account", mapper.url("https://server/opds?koharia=17-account"))
        assertEquals("https://komga/api/v1/books/17", mapper.url("https://komga/api/v1/books/17"))
    }

    @Test
    fun `cross connection references and duplicate ownership markers are rejected`() {
        for (value in listOf(
            "/lanraragi/18/archive/same",
            "koharia-local-v2://18/root/a",
            "https://kavita.invalid/18-account/api/Image/1",
            "https://server/image?kohariaScope=18%2Faccount",
            "https://server/image?kohariaScope=17%2Faccount&kohariaScope=17%2Faccount",
            "content://app.fixture.library-storage/document/18%7Caccount%7Ca",
        )) {
            assertThrows(IllegalArgumentException::class.java) { mapper.url(value) }
        }
    }

    @Test
    fun `history progress and bookmarks stay linked without rewriting notes or remote state`() {
        val chapter = "/kavita/17/account/chapter/1/2/3/4.epub"
        val restored = mapper.url(chapter)
        val locator = """{"href":"chapter17.xhtml","locations":{"progression":0.5}}"""
        val manga = BackupManga(
            17, "/kavita/17/account/series/2", title = "17", notes = chapter,
            chapters = listOf(BackupChapter(chapter, "17", read = true)),
            history = listOf(BackupHistory(chapter, 123, 456)),
            epubProgress = listOf(BackupEpubProgress(chapter, chapter, locator, updatedAt = 123)),
            epubBookmarks = listOf(BackupEpubBookmark(chapter, locator, note = chapter, createdAt = 123)),
            ttsProgress = listOf(BackupTtsProgress(chapter, 17, 123)),
            kavitaState = listOf(BackupKavitaState(chapter, "{\"remote\":17}", 17, true)),
        )
        mapper.manga(manga)
        assertEquals(91L, manga.source)
        assertEquals(restored, manga.chapters.single().url)
        assertEquals(restored, manga.history.single().url)
        assertEquals(456L, manga.history.single().readDuration)
        assertEquals(restored, manga.epubProgress.single().bookUrl)
        assertEquals(restored, manga.epubBookmarks.single().chapterUrl)
        assertEquals(locator, manga.epubBookmarks.single().locatorJson)
        assertEquals(chapter, manga.epubBookmarks.single().note)
        assertEquals(chapter, manga.notes)
        assertEquals(restored, manga.ttsProgress.single().chapterUrl)
        assertEquals(17L, manga.ttsProgress.single().sentenceIndex)
        assertEquals(BackupKavitaState(restored, "{\"remote\":17}", 17, true), manga.kavitaState.single())
    }

    @Test
    fun `all provider pending state links follow chapter remap`() {
        val chapter = "/lanraragi/17/archive/17"
        val manga = BackupManga(
            17,
            chapter,
            lanraragiState = listOf(BackupLanraragiState(chapter, 2, 10, 123)),
            smangaState = listOf(BackupSmangaState(chapter)),
            suwayomiState = listOf(BackupSuwayomiState(chapter, "{\"chapterId\":17}", 17, true)),
        )
        mapper.manga(manga)
        assertEquals(mapper.url(chapter), manga.lanraragiState.single().chapterUrl)
        assertEquals(mapper.url(chapter), manga.smangaState.single().chapterUrl)
        assertEquals(mapper.url(chapter), manga.suwayomiState.single().chapterUrl)
        assertEquals("{\"chapterId\":17}", manga.suwayomiState.single().payload)
    }

    @Test
    fun `annotation locator href remaps without rewriting highlighted text`() {
        val old = "https://kavita.invalid/17-account/api/Reader/book"
        val locator = JsonObject(mapOf("href" to JsonPrimitive(old), "text" to JsonPrimitive(old))).toString()
        val payload = JsonObject(
            mapOf("locatorJson" to JsonPrimitive(locator), "note" to JsonPrimitive(old)),
        ).toString()
        val manga = BackupManga(
            17,
            "/kavita/17/account/series/2",
            kavitaAnnotations = listOf(
                BackupKavitaAnnotation("/kavita/17/account/chapter/1/2/3/4.epub", "annotation", payload, 1, true),
            ),
        )
        mapper.manga(manga)
        val mappedPayload = Json.parseToJsonElement(manga.kavitaAnnotations.single().payload) as JsonObject
        assertEquals(JsonPrimitive(old), mappedPayload["note"])
        val mappedLocator = Json.parseToJsonElement(
            (mappedPayload["locatorJson"] as JsonPrimitive).content,
        ) as JsonObject
        assertEquals(JsonPrimitive(mapper.url(old)), mappedLocator["href"])
        assertEquals(JsonPrimitive(old), mappedLocator["text"])
    }

    @Test
    fun `network config remaps active detached and legacy roots and keeps credentials verbatim`() {
        val uri = "content://app.fixture.library-storage/document/17%7Caccount%7C"
        val config = LocalLibraryConfig(
            roots = listOf(LocalLibraryRootConfig(id = "17", treeUri = uri)),
            detachedRoots = listOf(LocalLibraryRootConfig(id = "detached", treeUri = uri)),
            managedBaseTreeUri = uri,
            treeUri = uri,
        )
        val result = mapper.preferences(
            BackupSourcePreferences(
                "source_17",
                listOf(
                    BackupPreference("local_library_config", StringPreferenceValue(Json.encodeToString(config))),
                    BackupPreference("credential", StringPreferenceValue("/kavita/17/account/")),
                    BackupPreference(
                        "local_custom_cover_urls",
                        StringSetPreferenceValue(setOf("koharia-local-v2://17/root/a")),
                    ),
                ),
            ),
        )
        assertEquals("source_91", result.sourceKey)
        val mapped = Json.decodeFromString<LocalLibraryConfig>((result.prefs[0].value as StringPreferenceValue).value)
        assertEquals(mapper.url(uri), mapped.roots.single().treeUri)
        assertEquals("17", mapped.roots.single().id)
        assertEquals(mapper.url(uri), mapped.detachedRoots.single().treeUri)
        assertEquals(mapper.url(uri), mapped.managedBaseTreeUri)
        assertEquals(mapper.url(uri), mapped.treeUri)
        assertEquals(StringPreferenceValue("/kavita/17/account/"), result.prefs[1].value)
        assertEquals(StringSetPreferenceValue(setOf("koharia-local-v2://91/root/a")), result.prefs[2].value)
    }
}

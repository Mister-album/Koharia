package koharia.kavita

import koharia.domain.kavita.KavitaCacheEntry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class KavitaDownloadedSortTest {
    private val identity = KavitaIdentity(130, "fixture")
    private val entries = (1L..3L).map { id ->
        Manga.create().copy(id = id, source = 130, url = identity.series(id), title = "Display $id")
    }
    private fun sort(
        field: Int,
        metadata: Map<Long, KavitaDownloadedMetadata>,
        descending: Boolean = false,
        cached: List<Long> = emptyList(),
        rows: List<Manga> = entries,
    ) = sortKavitaDownloads(rows, identity, "$field ${if (descending) "desc" else "asc"}", metadata, cached, 130)

    @Test fun serverNamesAndStableIdentityWinOverDisplayTitleAndInsertionOrder() {
        val data = mapOf(
            1L to KavitaDownloadedMetadata(KavitaSeries(1, sortName = "z"), null),
            2L to KavitaDownloadedMetadata(KavitaSeries(2, sortName = "A"), null),
            3L to KavitaDownloadedMetadata(KavitaSeries(3, sortName = "a"), null),
        )
        assertEquals(listOf(2L, 3L, 1L), sort(1, data, rows = entries.reversed()).entries.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), sort(1, data, descending = true).entries.map { it.id })
        assertFalse(sort(1, data).incomplete)
    }

    @Test fun datesUseServerValuesAndKeepMissingOrInvalidValuesLastInBothDirections() {
        for (field in listOf(2, 4, 7)) {
            fun series(id: Long, date: String) = KavitaSeries(
                id,
                created = date,
                lastChapterAdded = date,
                latestReadDate = date,
                pagesRead = if (id == 1L) 1 else 100,
            )
            val data = mapOf(
                1L to KavitaDownloadedMetadata(series(1, "2025-01-02T00:00:00Z"), null),
                2L to KavitaDownloadedMetadata(series(2, "2025-01-01T00:00:00"), null),
                3L to KavitaDownloadedMetadata(series(3, "invalid"), null),
            )
            assertEquals(listOf(2L, 1L, 3L), sort(field, data).entries.map { it.id })
            assertEquals(listOf(1L, 2L, 3L), sort(field, data, true).entries.map { it.id })
            assertTrue(sort(field, data).incomplete)
        }
    }

    @Test fun hoursAndReleaseYearRetainZeroAndDistinguishUnknownValues() {
        val data = mapOf(
            1L to KavitaDownloadedMetadata(KavitaSeries(1, avgHoursToRead = 1.5), 2025),
            2L to KavitaDownloadedMetadata(KavitaSeries(2, avgHoursToRead = 0.0), 0),
        )
        for (field in listOf(5, 6)) {
            assertEquals(listOf(2L, 1L, 3L), sort(field, data).entries.map { it.id })
            assertEquals(listOf(1L, 2L, 3L), sort(field, data, true).entries.map { it.id })
            assertTrue(sort(field, data).incomplete)
        }
    }

    @Test fun updatedAndExternalRatingUseServerOrderWithoutReversingItTwice() {
        for (field in listOf(3, 8)) {
            assertEquals(listOf(3L, 1L, 2L), sort(field, emptyMap(), true, listOf(3, 1)).entries.map { it.id })
            assertTrue(sort(field, emptyMap(), true, listOf(3, 1)).incomplete)
            assertFalse(sort(field, emptyMap(), cached = listOf(2, 3, 1)).incomplete)
        }
    }

    @Test fun randomOrderSurvivesDeletionAndInputReorderingUntilTheSeedChanges() {
        val rows = (1L..40L).map { Manga.create().copy(id = it, url = identity.series(it), title = "$it") }
        val first = sort(9, emptyMap(), rows = rows).entries
        assertFalse(sort(9, emptyMap(), rows = rows).incomplete)
        assertEquals(first, sort(9, emptyMap(), rows = rows.reversed()).entries)
        assertEquals(
            first.filterNot {
                it.id == 10L
            },
            sort(9, emptyMap(), rows = rows.filterNot { it.id == 10L }).entries,
        )
        assertTrue(first != rows && first != rows.reversed())
        assertTrue(first != sortKavitaDownloads(rows, identity, "9 asc", emptyMap(), emptyList(), 131).entries)
        assertEquals(first.reversed(), sort(9, emptyMap(), true, rows = rows).entries)
    }

    @Test fun unsupportedLegacyFieldsAreExplicitlyIncompleteInsteadOfPretendingToSort() {
        assertEquals((1..9).toSet(), kavitaSortOptions().map { it.first }.toSet())
        assertTrue(sort(10, emptyMap()).incomplete)
        assertTrue(sort(11, emptyMap()).incomplete)
    }

    @Test fun oldJsonKeepsMissingSortFactsUnknown() {
        val api = api()
        try {
            val old = api.json.decodeFromString<KavitaSeries>("""{"id":1,"name":"legacy"}""")
            assertEquals(null, old.created)
            assertEquals(null, old.avgHoursToRead)
            assertEquals(null, old.sortName)
            assertTrue(sort(1, mapOf(1L to KavitaDownloadedMetadata(old, null))).incomplete)
            val value = api.json.decodeFromString<KavitaSeries>(
                """{"id":2,"sortName":"a","created":"2025-01-01T00:00:00","avgHoursToRead":2.5}""",
            )
            assertEquals(2.5, value.avgHoursToRead)
        } finally {
            api.close()
        }
    }

    @Test fun cachedMetadataAndPageOrderRemainAccountScopedAndNeverFetch(): Unit = runBlocking {
        val repository = MemoryKavitaRepository()
        val api = api()
        val catalog = KavitaCatalog(130, "fixture", repository, api) {}
        try {
            val series = KavitaSeries(1, sortName = "Saved", created = "2025-01-01T00:00:00")
            repository.putCache(
                130,
                "fixture",
                "series/1",
                KavitaCacheEntry("data", api.json.encodeToString(series), 1),
            )
            repository.putCache(130, "foreign", "series/1", KavitaCacheEntry("data", "invalid", 99))
            repository.putCache(131, "fixture", "series/1", KavitaCacheEntry("data", "invalid", 99))
            repository.putCache(130, "fixture", "metadata/1", KavitaCacheEntry("data", """{"releaseYear":2000}""", 1))
            repository.putCache(130, "fixture", "series/2", KavitaCacheEntry("data", "invalid", 1))
            val metadata = catalog.downloadedMetadata(listOf(1, 2, 3))
            assertEquals(series, metadata.getValue(1).series)
            assertEquals(2000, metadata.getValue(1).releaseYear)
            assertEquals(null, metadata.getValue(2).series)
            assertEquals(null, metadata.getValue(3).series)
            val filter = KavitaFilter(sortOptions = KavitaSort(3, true))
            val group = "shelf/" + ("Series/all-v2" + api.json.encodeToString(filter)).encodeUtf8().sha256().hex()
            for ((page, id) in listOf("10" to 3L, "2" to 2L, "1" to 1L)) {
                repository.putCache(
                    130,
                    "fixture",
                    group,
                    KavitaCacheEntry(
                        page,
                        api.json.encodeToString(KavitaSeriesPage(listOf(KavitaSeries(id)), false)),
                        1,
                    ),
                )
            }
            assertEquals(listOf(1L, 2L, 3L), catalog.cachedSeriesOrder(filter))
            assertEquals(listOf(3L, 2L, 1L), catalog.cachedSeriesOrder(filter.copy(sortOptions = KavitaSort(3, false))))
            repository.invalidate(130, "fixture")
            assertTrue(catalog.cachedSeriesOrder(filter).isEmpty())
            assertEquals(null, catalog.downloadedMetadata(listOf(1)).getValue(1).series)
        } finally {
            api.close()
        }
    }

    private fun api() = KavitaApiClient(
        OkHttpClient.Builder().addInterceptor { error("Downloaded sorting must not fetch") }.build(),
        "http://127.0.0.1:9",
        "fixture",
        "fixture",
    )
}

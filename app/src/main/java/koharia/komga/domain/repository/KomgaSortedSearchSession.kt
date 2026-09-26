package koharia.komga.domain.repository

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import koharia.komga.api.KomgaApiClient.SearchType
import koharia.komga.api.dto.offlineFilterMetadata
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.concurrent.ConcurrentHashMap

/** One session belongs to one PagingSource. Remote pages survive a failed output-page attempt. */
internal class KomgaSortedSearchSession(
    private val sortIndex: Int,
    private val ascending: Boolean,
    private val validate: () -> Unit = {},
    private val fetch: suspend (SearchType, Int) -> MangasPage,
) {
    private data class Cursor(var page: Int = 1, var offset: Int = 0, var ended: Boolean = false)

    private val mutex = Mutex()
    private val pages = ConcurrentHashMap<Pair<SearchType, Int>, MangasPage>()
    private var books = Cursor()
    private var series = Cursor()
    private var lastPage = 0
    private var lastResult: MangasPage? = null

    suspend fun load(page: Int): MangasPage = mutex.withLock {
        currentCoroutineContext().ensureActive()
        validate()
        if (page == lastPage) return@withLock checkNotNull(lastResult)
        require(page == lastPage + 1)
        val nextBooks = books.copy()
        val nextSeries = series.copy()
        suspend fun head(type: SearchType, cursor: Cursor): SManga? {
            while (!cursor.ended) {
                val key = type to cursor.page
                val result = pages[key] ?: fetch(type, cursor.page).also { pages[key] = it }
                result.mangas.getOrNull(cursor.offset)?.let { return it }
                check(result.mangas.isNotEmpty() || !result.hasNextPage) { "Empty non-terminal search page" }
                if (!result.hasNextPage) {
                    cursor.ended = true
                } else {
                    cursor.page++
                    cursor.offset = 0
                }
            }
            return null
        }
        val result = buildList {
            while (size < 25) {
                val (book, serial) = coroutineScope {
                    val book = async { head(SearchType.BOOKS, nextBooks) }
                    val series = async { head(SearchType.SERIES, nextSeries) }
                    book.await() to series.await()
                }
                if (book == null && serial == null) break
                if (serial == null || (
                        book != null && compareKomgaSearchTime(
                            book.memo.komgaSearchTimestamp(sortIndex),
                            book.url,
                            serial.memo.komgaSearchTimestamp(sortIndex),
                            serial.url,
                            ascending,
                        ) <= 0
                        )
                ) {
                    add(checkNotNull(book))
                    nextBooks.offset++
                } else {
                    add(serial)
                    nextSeries.offset++
                }
            }
        }
        val hasNext = listOf(SearchType.BOOKS to nextBooks, SearchType.SERIES to nextSeries).any { (type, cursor) ->
            !cursor.ended &&
                pages[type to cursor.page]?.let { cursor.offset < it.mangas.size || it.hasNextPage } == true
        }
        currentCoroutineContext().ensureActive()
        validate()
        books = nextBooks
        series = nextSeries
        lastPage = page
        MangasPage(result, hasNext).also {
            lastResult = it
            pages.keys.removeAll { (type, number) ->
                number < if (type ==
                    SearchType.BOOKS
                ) {
                    books.page
                } else {
                    series.page
                }
            }
        }
    }
}

internal fun kotlinx.serialization.json.JsonObject.komgaSearchTimestamp(sortIndex: Int): Instant? {
    val metadata = offlineFilterMetadata() ?: return null
    val date = if (sortIndex == 2) metadata.createdDate else metadata.lastModifiedDate
    if (date == null) return null
    return try {
        val parsed = DateTimeFormatter.ISO_DATE_TIME.parse(date)
        if (parsed.isSupported(ChronoField.INSTANT_SECONDS)) {
            Instant.from(parsed)
        } else {
            LocalDateTime.from(parsed).toInstant(ZoneOffset.UTC)
        }
    } catch (_: DateTimeParseException) {
        null
    }
}

internal fun compareKomgaSearchTime(a: Instant?, aUrl: String, b: Instant?, bUrl: String, ascending: Boolean): Int {
    val time = when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        ascending -> a.compareTo(b)
        else -> b.compareTo(a)
    }
    return time.takeIf { it != 0 } ?: aUrl.substringAfter("/api/v1/").compareTo(bUrl.substringAfter("/api/v1/"))
}

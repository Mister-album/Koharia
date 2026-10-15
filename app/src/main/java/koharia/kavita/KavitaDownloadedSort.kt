package koharia.kavita

import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.random.Random

internal fun kavitaSortOptions() = listOf(
    1 to MR.strings.kavita_sort_name,
    2 to MR.strings.kavita_sort_created,
    3 to MR.strings.kavita_sort_updated,
    4 to MR.strings.kavita_sort_chapter_added,
    6 to MR.strings.kavita_release_year,
    7 to MR.strings.action_sort_last_read,
    8 to MR.strings.kavita_average_rating,
    5 to MR.strings.kavita_read_hours,
    9 to MR.strings.kavita_random,
)

internal data class KavitaDownloadedMetadata(val series: KavitaSeries?, val releaseYear: Int?)

internal data class KavitaDownloadedSortResult(val entries: List<Manga>, val incomplete: Boolean)

internal fun sortKavitaDownloads(
    entries: List<Manga>,
    identity: KavitaIdentity,
    order: String,
    metadata: Map<Long, KavitaDownloadedMetadata>,
    cachedOrder: List<Long>,
    randomSeed: Long,
): KavitaDownloadedSortResult {
    val field = order.substringBefore(' ').toIntOrNull()
    val descending = order.endsWith("desc")
    val ranks = cachedOrder.withIndex().associate { it.value to it.index.toDouble() }
    data class Entry(val manga: Manga, val id: Long, val name: String, val value: Double?)
    val values = entries.map { manga ->
        val id = identity.seriesId(manga.url)
        val data = metadata[id]
        val series = data?.series
        val name = series?.sortName?.ifBlank { series.name }?.ifBlank { manga.title } ?: manga.title
        val value = when (field) {
            1 -> if (series?.sortName == null) null else 0.0
            2 -> series?.created?.cachedSortDate()
            4 -> series?.lastChapterAdded?.cachedSortDate()
            5 -> series?.avgHoursToRead?.takeIf { it.isFinite() }
            6 -> data?.releaseYear?.toDouble()
            7 -> series?.latestReadDate?.cachedSortDate()
            9 -> Random(randomSeed xor id).nextDouble()
            // These values are not in SeriesDto; preserve the server's cached order instead of
            // substituting local timestamps or the user's own rating for external average rating.
            3, 8 -> ranks[id]
            else -> null
        }
        Entry(manga, id, name.lowercase(Locale.ROOT), value)
    }
    val sorted = values.sortedWith { a, b ->
        val missing = compareValues(a.value == null, b.value == null)
        if (missing != 0) {
            missing
        } else {
            val primary = if (field == 1) compareValues(a.name, b.name) else compareValues(a.value, b.value)
            val directed = if (descending && field !in listOf(3, 8)) -primary else primary
            directed.takeIf { it != 0 } ?: compareValues(a.name, b.name).takeIf { it != 0 }
                ?: compareValues(a.id, b.id)
        }
    }
    return KavitaDownloadedSortResult(sorted.map { it.manga }, values.any { it.value == null })
}

private fun String.cachedSortDate(): Double? = runCatching { Instant.parse(this).toEpochMilli().toDouble() }
    .getOrElse {
        runCatching { LocalDateTime.parse(this).toInstant(ZoneOffset.UTC).toEpochMilli().toDouble() }.getOrNull()
    }

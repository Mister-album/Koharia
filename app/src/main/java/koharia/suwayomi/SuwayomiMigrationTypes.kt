package koharia.suwayomi

import kotlin.math.max
import kotlin.math.min

/**
 * What to carry from the old library entry to the new one. Mirrors the options the server client
 * offers, including the Copy/Migrate choice that decides whether the old entry leaves the library.
 * Offline/download state is carried by [migrateDownloads]; there is no separate offline option,
 * because the client keeps no per-entry offline settings of its own.
 */
data class SuwayomiMigrationOptions(
    val migrateChapters: Boolean = true,
    val migrateCategories: Boolean = true,
    val migrateTracking: Boolean = true,
    val migrateReaderSettings: Boolean = true,
    val migrateDownloads: Boolean = false,
    val removeSource: Boolean = true,
)

/** Controls that only affect which rows the run screen lists. */
data class SuwayomiMigrationRunFilters(
    val hideUnmatched: Boolean = false,
    val hideWithoutUpdates: Boolean = false,
)

/** One library entry selected for migration, resolved against the target sources on the run screen. */
data class SuwayomiMigrationCandidate(
    val from: SuwayomiManga,
    val fromSourceName: String,
)

enum class SuwayomiMigrationPhase { QUEUED, SEARCHING, READY, NO_MATCH, COPYING, DONE, FAILED }

data class SuwayomiMigrationMatch(
    val target: SuwayomiManga?,
    val targetSourceName: String?,
    val confidence: Double,
)

/**
 * Query forms tried against one target source, most specific first. Release titles often glue a
 * Japanese name to a Chinese subtitle (`メイド教育女仆教育。没落貴族瑠璃川椿`), and a source's search
 * rarely matches that concatenation, so the leading kana run and the trailing han run are also
 * offered as standalone queries.
 */
fun suwayomiMigrationQueries(title: String, extraSearchQuery: String?): List<String> {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return emptyList()
    val queries = LinkedHashSet<String>()
    fun add(value: String) {
        val candidate = value.trim().trim(*QUERY_TRIM_CHARS)
        if (candidate.length >= MIN_QUERY_LENGTH) queries += candidate
    }

    extraSearchQuery?.trim()?.takeIf(String::isNotEmpty)?.let { add("$trimmed $it") }
    add(trimmed)

    // Bracketed groups, volumes and remaster tags are usually absent from the target's title.
    val segments = SEGMENT_SEPARATOR.split(trimmed).map { it.trim() }.filter { it.length >= 2 }
    segments.sortedByDescending { it.length }.forEach { add(it) }

    // A Japanese title glued to a Chinese subtitle, with no separator between them.
    segments.forEach { segment ->
        val leadingKana = segment.takeWhile { it.isKana() }
        val trailingHan = segment.takeLastWhile { it.isHan() }
        add(leadingKana)
        add(trailingHan)
        if (leadingKana.length < segment.length) add(segment.removePrefix(leadingKana))
        if (trailingHan.length < segment.length) add(segment.removeSuffix(trailingHan))
    }

    return queries.toList()
}

private const val MIN_QUERY_LENGTH = 2
private val QUERY_TRIM_CHARS = charArrayOf('-', '–', '—', '|', '·', '。', '、', ',', '/', ':', '：')
private val SEGMENT_SEPARATOR = Regex("""[\[\]【】()（）|｜/／:：;；,，、~～]+""")

private fun Char.isKana(): Boolean = this in '\u3040'..'\u30ff' || this in '\u31f0'..'\u31ff'

private fun Char.isHan(): Boolean = this in '\u4e00'..'\u9fff' || this in '\uf900'..'\ufaff'

/**
 * Token/bigram similarity used to pick between several target results. An exact match wins, a
 * shorter title contained in a longer one scores by coverage, and otherwise the bigram overlap
 * (Sørensen–Dice) decides.
 */
fun suwayomiTitleSimilarity(left: String, right: String): Double {
    val a = left.lowercase().filter { it.isLetterOrDigit() }
    val b = right.lowercase().filter { it.isLetterOrDigit() }
    if (a.isEmpty() || b.isEmpty()) return 0.0
    if (a == b) return 1.0
    val shorter = if (a.length <= b.length) a else b
    val longer = if (a.length <= b.length) b else a
    val containment = if (longer.contains(shorter)) {
        min(1.0, shorter.length.toDouble() / longer.length)
    } else {
        0.0
    }
    val bigrams = { value: String ->
        value.windowed(2, 1).toMutableSet().also { if (value.length == 1) it += value }
    }
    val left2 = bigrams(a)
    val right2 = bigrams(b)
    val shared = left2.count { it in right2 }
    val dice = 2.0 * shared / (left2.size + right2.size)
    // Weigh containment heavily so a clear prefix match beats a shared-substring coincidence.
    return max(dice, containment * 0.95)
}

/** Chooses the best available result, requiring a minimum confidence to count as a match. */
fun bestSuwayomiMatch(
    title: String,
    results: List<SuwayomiManga>,
    minimumConfidence: Double = 0.6,
): SuwayomiManga? = results
    .map { it to suwayomiTitleSimilarity(title, it.title) }
    .filter { it.second >= minimumConfidence }
    .maxByOrNull { it.second }
    ?.first

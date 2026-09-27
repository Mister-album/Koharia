package koharia.kavita

import koharia.epub.settings.EpubLayoutPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

fun kavitaHighlightColors(preferences: JsonObject): Map<Int, String> =
    (preferences["bookReaderHighlightSlots"] as? JsonArray).orEmpty().mapNotNull { item ->
        val value = item as? JsonObject ?: return@mapNotNull null
        val slot = (value["slotNumber"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        val color = value["color"] as? JsonObject ?: return@mapNotNull null
        val r = (color["r"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        val g = (color["g"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        val b = (color["b"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        val a = (color["a"] as? JsonPrimitive)?.floatOrNull ?: return@mapNotNull null
        if (slot !in 0..3 || r !in 0..255 || g !in 0..255 || b !in 0..255 || !a.isFinite() || a !in 0f..1f) {
            return@mapNotNull null
        }
        slot to "rgba($r,$g,$b,$a)"
    }.toMap()

/** Only whitelisted status fields may be cached: the wire DTO also contains third-party tokens. */
@Serializable
data class KavitaScrobbleStatus(
    val provider: Int,
    val userName: String = "",
    val validUntilUtc: String = "",
    val lastSyncedUtc: String = "",
    val settings: KavitaScrobbleSettings = KavitaScrobbleSettings(),
)

@Serializable
data class KavitaScrobbleSettings(
    val progressScrobbling: Boolean = false,
    val wantToReadSync: Boolean = false,
    val ratingScrobbling: Boolean = false,
    val reviewsScrobbling: Boolean = false,
)

data class KavitaProfileImport(
    val fontScale: Float?,
    val lineHeight: Float?,
    val direction: EpubLayoutPreferences.PageDirection?,
    val readingMode: EpubLayoutPreferences.ReadingMode?,
) {
    val isEmpty get() = fontScale == null && lineHeight == null && direction == null && readingMode == null

    /** Called only after the preview is explicitly confirmed; callers supply the shared preference scope. */
    fun applyTo(preferences: EpubLayoutPreferences) {
        fontScale?.let(preferences.fontSize::set)
        lineHeight?.let {
            preferences.lineHeight.set(it)
            preferences.spacingMode.set(EpubLayoutPreferences.SpacingMode.CUSTOM)
        }
        direction?.let(preferences.pageDirection::set)
        readingMode?.let(preferences.readingMode::set)
        if (fontScale != null || lineHeight != null) preferences.publisherStyles.set(false)
    }

    companion object {
        fun from(value: JsonObject): KavitaProfileImport {
            fun number(key: String) = (value[key] as? JsonPrimitive)?.intOrNull
            return KavitaProfileImport(
                number("bookReaderFontSize")?.div(100f)?.takeIf { it in 0.8125f..2f },
                number("bookReaderLineSpacing")?.div(100f)?.takeIf { it in 1f..3f },
                when (number("bookReaderReadingDirection")) {
                    0 -> EpubLayoutPreferences.PageDirection.LEFT_TO_RIGHT
                    1 -> EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT
                    else -> null
                },
                when (number("bookReaderLayoutMode")) {
                    0 -> EpubLayoutPreferences.ReadingMode.SCROLL
                    1 -> EpubLayoutPreferences.ReadingMode.PAGINATED
                    // Two-column layout has no exact equivalent in the native reader.
                    else -> null
                },
            )
        }
    }
}

internal fun kavitaProviderName(value: Long): String = when (value) {
    0L -> "Kavita"
    1L -> "AniList"
    2L -> "MyAnimeList"
    4L -> "Comic Book Roundup"
    5L -> "Hardcover"
    6L -> "MangaBaka"
    else -> value.toString()
}

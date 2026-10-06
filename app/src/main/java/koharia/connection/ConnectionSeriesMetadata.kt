package koharia.connection

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Common persisted metadata keys; provider DTOs are mapped before reaching the details UI. */
data class ConnectionSeriesMetadata(
    val publisher: String? = null,
    val language: String? = null,
    val ageRating: Int? = null,
    val booksCount: Int? = null,
    val totalBookCount: Int? = null,
) {
    companion object {
        fun fromMemo(memo: JsonObject) = ConnectionSeriesMetadata(
            publisher = memo.text("publisher"),
            language = memo.text("language"),
            ageRating = memo.number("ageRating")?.takeIf { it >= 0 },
            booksCount = memo.number("booksCount")?.takeIf { it > 0 },
            totalBookCount = memo.number("totalBookCount")?.takeIf { it > 0 },
        )

        private fun JsonObject.text(key: String): String? =
            (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

        private fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
    }
}

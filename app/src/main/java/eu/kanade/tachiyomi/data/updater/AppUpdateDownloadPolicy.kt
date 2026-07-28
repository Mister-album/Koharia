package eu.kanade.tachiyomi.data.updater

internal object AppUpdateDownloadPolicy {

    private val retryableHttpCodes = setOf(408, 429, 500, 502, 503, 504)
    private val contentRangeRegex = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)

    fun isRetryableHttpCode(code: Int): Boolean = code in retryableHttpCodes

    fun parseContentRange(value: String?): ContentRange? {
        val match = value?.let(contentRangeRegex::matchEntire) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val endInclusive = match.groupValues[2].toLongOrNull() ?: return null
        if (endInclusive < start) return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (total != null && endInclusive >= total) return null
        return ContentRange(start, endInclusive, total)
    }

    data class ContentRange(
        val start: Long,
        val endInclusive: Long,
        val total: Long?,
    )
}

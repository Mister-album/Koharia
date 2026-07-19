package tachiyomi.core.common

import android.content.Context
import java.util.Locale

object DocumentationUrls {

    fun gettingStarted(context: Context) = localized(context, "docs/guides/getting-started")

    fun troubleshooting(context: Context) = localized(context, "docs/guides/troubleshooting/")

    fun library(context: Context) = localized(context, "docs/faq/library")

    fun storage(context: Context) = localized(context, "docs/faq/storage")

    fun privacy(context: Context) = localized(context, "privacy/")

    private fun localized(context: Context, path: String): String {
        val locales = context.resources.configuration.locales
        val language = if (locales.isEmpty) Locale.getDefault().language else locales[0].language
        val localePath = if (language.lowercase(Locale.ROOT) in CHINESE_LANGUAGE_CODES) "zh" else "en"
        return "$BASE_URL/$localePath/$path"
    }

    private const val BASE_URL = "https://koharia.org"

    // Include common ISO codes for Chinese macrolanguage varieties in addition to
    // Simplified/Traditional BCP 47 tags, whose normalized language is `zh`.
    private val CHINESE_LANGUAGE_CODES = setOf(
        "zh",
        "zho",
        "chi",
        "cmn",
        "yue",
        "wuu",
        "hak",
        "nan",
        "gan",
        "hsn",
        "lzh",
    )
}

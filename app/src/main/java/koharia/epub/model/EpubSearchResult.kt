package koharia.epub.model

import org.readium.r2.shared.publication.Locator

data class EpubSearchResult(
    val locator: Locator,
    val title: String?,
    val before: String?,
    val highlight: String?,
    val after: String?,
)

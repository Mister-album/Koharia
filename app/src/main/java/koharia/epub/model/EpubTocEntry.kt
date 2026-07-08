package koharia.epub.model

import org.readium.r2.shared.publication.Link

data class EpubTocEntry(
    val title: String,
    val link: Link,
    val depth: Int,
)

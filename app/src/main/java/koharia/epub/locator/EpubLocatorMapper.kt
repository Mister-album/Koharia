package koharia.epub.locator

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url

fun Publication.toNavigatorLocator(locator: Locator): Locator {
    val resource = findResource(locator.href.toString()) ?: return locator
    return locator.copy(href = resource.navigatorHref.withFragmentFrom(locator.href.toString()))
}

fun Publication.toPersistentLocator(locator: Locator): Locator {
    val resource = findResource(locator.href.toString()) ?: return locator
    val href = Url(resource.persistentHref.withFragmentFrom(locator.href.toString())) ?: return locator
    return locator.copy(href = href)
}

private fun Publication.findResource(href: String): ResourceHref? {
    val target = href.normalizedHref()
    return readingOrder
        .asSequence()
        .map(Link::toResourceHref)
        .firstOrNull { resource ->
            resource.aliases.any { alias ->
                alias == target || alias.endsWith("/$target") || target.endsWith("/$alias")
            }
        }
}

private fun Link.toResourceHref(): ResourceHref {
    val navigatorHref = url()
    val linkHref = href.toString().normalizedHref()
    val navigatorAlias = navigatorHref.toString().normalizedHref()
    val persistentHref = when {
        "/resource/" in linkHref -> linkHref.substringAfter("/resource/")
        !linkHref.hasScheme() -> linkHref
        "/resource/" in navigatorAlias -> navigatorAlias.substringAfter("/resource/")
        else -> linkHref
    }.trimStart('/')

    return ResourceHref(
        navigatorHref = navigatorHref,
        persistentHref = persistentHref,
        aliases = setOf(linkHref, navigatorAlias, persistentHref),
    )
}

private fun String.normalizedHref(): String =
    substringBefore('#')
        .substringBefore('?')
        .trimStart('/')

private fun String.hasScheme(): Boolean = substringBefore('/').contains(':')

private fun Url.withFragmentFrom(originalHref: String): Url {
    val fragment = originalHref.substringAfter('#', missingDelimiterValue = "")
    if (fragment.isBlank()) return this
    return Url("${toString().substringBefore('#')}#$fragment") ?: this
}

private fun String.withFragmentFrom(originalHref: String): String {
    val fragment = originalHref.substringAfter('#', missingDelimiterValue = "")
    return if (fragment.isBlank()) this else "${substringBefore('#')}#$fragment"
}

private data class ResourceHref(
    val navigatorHref: Url,
    val persistentHref: String,
    val aliases: Set<String>,
)

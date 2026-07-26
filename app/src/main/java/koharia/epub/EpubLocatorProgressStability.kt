package koharia.epub

import org.readium.r2.shared.publication.Locator

internal fun Locator.preserveProgressMetricsFrom(previous: Locator?): Locator {
    previous ?: return this
    if (!href.toString().isSameEpubResource(previous.href.toString())) return this

    val stablePosition = previous.locations.position ?: locations.position
    val stableTotalProgression = previous.locations.totalProgression ?: locations.totalProgression
    if (stablePosition == locations.position && stableTotalProgression == locations.totalProgression) {
        return this
    }
    return copy(
        locations = locations.copy(
            position = stablePosition,
            totalProgression = stableTotalProgression,
        ),
    )
}

private fun String.isSameEpubResource(other: String): Boolean {
    val first = resourceKey()
    val second = other.resourceKey()
    if (first.isBlank() || second.isBlank()) return false
    return first == second || first.endsWith("/$second") || second.endsWith("/$first")
}

private fun String.resourceKey(): String =
    substringBefore('#')
        .substringBefore('?')
        .trimStart('/')

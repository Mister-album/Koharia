package koharia.epub

import org.readium.r2.shared.publication.Locator
import java.net.URI

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

internal fun Locator.alignToEpubPositions(positions: List<Locator>): Locator {
    val targetProgression = locations.progression ?: return this
    val resourcePositions = positions.mapNotNull { position ->
        if (!position.href.toString().isSameEpubResource(href.toString())) return@mapNotNull null
        val progression = position.locations.progression ?: return@mapNotNull null
        position to progression
    }
    if (resourcePositions.isEmpty()) return this

    val alignedPosition = resourcePositions
        .filter { (_, progression) -> progression <= targetProgression }
        .maxByOrNull { (_, progression) -> progression }
        ?: resourcePositions.minBy { (_, progression) -> progression }
    val authoritativeLocations = alignedPosition.first.locations
    val alignedProgression = alignedPosition.second
    val alignedPositionIndex = authoritativeLocations.position ?: locations.position
    val alignedTotalProgression = authoritativeLocations.totalProgression ?: locations.totalProgression
    if (
        alignedProgression == locations.progression &&
        alignedPositionIndex == locations.position &&
        alignedTotalProgression == locations.totalProgression
    ) {
        return this
    }

    return copy(
        locations = locations.copy(
            progression = alignedProgression,
            position = alignedPositionIndex,
            totalProgression = alignedTotalProgression,
        ),
    )
}

internal fun String.isSameEpubResource(other: String): Boolean {
    val first = canonicalEpubResourcePath()
    val second = other.canonicalEpubResourcePath()
    if (first.isBlank() || second.isBlank()) return false
    return first == second
}

private fun String.canonicalEpubResourcePath(): String {
    val resourceHref = substringBefore('#')
        .substringBefore('?')
        .trim()
    if (resourceHref.isBlank()) return ""

    val uri = runCatching { URI(resourceHref) }.getOrNull()
    val servedPath = if (uri?.rawAuthority.equals(READIUM_PACKAGE_AUTHORITY, ignoreCase = true)) {
        uri?.rawPath.orEmpty()
    } else {
        resourceHref
    }
    return servedPath
        .substringAfter(READIUM_RESOURCE_PATH_PREFIX, missingDelimiterValue = servedPath)
        .trimStart('/')
}

private const val READIUM_PACKAGE_AUTHORITY = "readium_package"
private const val READIUM_RESOURCE_PATH_PREFIX = "/resource/"

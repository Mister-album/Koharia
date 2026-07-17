package koharia.epub.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.PositionsService
import org.readium.r2.shared.util.mediatype.MediaType

class EpubPositionsController(
    private val readingOrder: List<Link>,
    private val delegate: PositionsService?,
    initialPositions: List<Locator> = emptyList(),
) : PositionsService {
    private val refreshMutex = Mutex()

    @Volatile
    private var current: List<List<Locator>> = initialPositions
        .groupForReadingOrder()
        .takeIf { groups -> groups.flatten().isNotEmpty() }
        ?: fallbackPositions()

    override suspend fun positionsByReadingOrder(): List<List<Locator>> = current

    suspend fun refresh(): List<Locator> = refreshMutex.withLock {
        val refreshed = delegate?.positionsByReadingOrder()
            ?.takeIf { groups -> groups.flatten().isNotEmpty() }
        if (refreshed != null) current = refreshed
        current.flatten()
    }

    fun currentPositions(): List<Locator> = current.flatten()

    override fun close() {
        delegate?.close()
    }

    private fun List<Locator>.groupForReadingOrder(): List<List<Locator>> =
        readingOrder.map { link ->
            val linkHref = link.href.toString().resourceKey()
            filter { locator ->
                val locatorHref = locator.href.toString().resourceKey()
                linkHref == locatorHref ||
                    linkHref.endsWith("/$locatorHref") ||
                    locatorHref.endsWith("/$linkHref")
            }
        }

    private fun fallbackPositions(): List<List<Locator>> {
        val count = readingOrder.size.coerceAtLeast(1)
        return readingOrder.mapIndexed { index, link ->
            listOf(
                Locator(
                    href = link.url(),
                    mediaType = link.mediaType ?: MediaType.XHTML,
                    title = link.title,
                    locations = Locator.Locations(
                        progression = 0.0,
                        position = index + 1,
                        totalProgression = index.toDouble() / count,
                    ),
                ),
            )
        }
    }

    private fun String.resourceKey(): String =
        substringBefore('#')
            .substringBefore('?')
            .trimStart('/')
}

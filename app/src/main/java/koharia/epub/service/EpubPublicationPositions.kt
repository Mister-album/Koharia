package koharia.epub.service

import koharia.epub.session.EpubPositionsController
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.CacheService
import org.readium.r2.shared.publication.services.ContentProtectionService
import org.readium.r2.shared.publication.services.CoverService
import org.readium.r2.shared.publication.services.LocatorService
import org.readium.r2.shared.publication.services.PositionsService
import org.readium.r2.shared.publication.services.cacheServiceFactory
import org.readium.r2.shared.publication.services.content.ContentService
import org.readium.r2.shared.publication.services.content.contentServiceFactory
import org.readium.r2.shared.publication.services.contentProtectionServiceFactory
import org.readium.r2.shared.publication.services.coverServiceFactory
import org.readium.r2.shared.publication.services.locatorServiceFactory
import org.readium.r2.shared.publication.services.positionsServiceFactory
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.publication.services.search.searchServiceFactory

internal data class EpubPublicationWithPositions(
    val publication: Publication,
    val controller: EpubPositionsController,
)

@OptIn(InternalReadiumApi::class, ExperimentalReadiumApi::class)
internal fun Publication.withEpubPositionsController(
    initialPositions: List<Locator> = emptyList(),
): EpubPublicationWithPositions {
    val original = this
    val controller = EpubPositionsController(
        readingOrder = readingOrder,
        delegate = findService(PositionsService::class),
        initialPositions = initialPositions,
    )
    val servicesBuilder = Publication.ServicesBuilder().apply {
        original.findService(CacheService::class)?.let { service ->
            cacheServiceFactory = { service }
        }
        original.findService(ContentService::class)?.let { service ->
            contentServiceFactory = { service }
        }
        original.findService(ContentProtectionService::class)?.let { service ->
            contentProtectionServiceFactory = { service }
        }
        original.findService(CoverService::class)?.let { service ->
            coverServiceFactory = { service }
        }
        original.findService(LocatorService::class)?.let { service ->
            locatorServiceFactory = { service }
        }
        positionsServiceFactory = { controller }
        original.findService(SearchService::class)?.let { service ->
            searchServiceFactory = { service }
        }
    }
    return EpubPublicationWithPositions(
        publication = Publication(
            manifest = manifest,
            container = container,
            servicesBuilder = servicesBuilder,
        ),
        controller = controller,
    )
}

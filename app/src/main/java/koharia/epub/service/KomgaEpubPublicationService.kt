package koharia.epub.service

import android.app.Application
import koharia.epub.model.EpubOpenRequest
import koharia.epub.session.EpubReaderSession
import logcat.LogPriority
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.FileExtension
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.asset.ResourceAsset
import org.readium.r2.shared.util.format.Format
import org.readium.r2.shared.util.format.FormatSpecification
import org.readium.r2.shared.util.format.Specification
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.fetchString
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.StringResource
import org.readium.r2.shared.util.resource.filename
import org.readium.r2.shared.util.resource.mediaType
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KomgaEpubPublicationService(
    private val application: Application = Injekt.get(),
    private val httpClientFactory: KomgaReadiumHttpClient = Injekt.get(),
) {

    suspend fun open(
        request: EpubOpenRequest,
        initialLocator: Locator?,
    ): EpubReaderSession {
        val bookUrl = requireNotNull(request.bookUrl) { "Missing Komga book URL" }
        val manifestUrl = requireNotNull(AbsoluteUrl("$bookUrl/manifest/epub")) {
            "Invalid Komga manifest URL"
        }
        logcat(LogPriority.DEBUG) {
            "EPUB remote open start chapterId=${request.chapterId} manifestUrl=$manifestUrl"
        }
        val httpClient = httpClientFactory.create(request.sourceId)
        val assetRetriever = AssetRetriever(application.contentResolver, httpClient)
        val publicationOpener = PublicationOpener(
            DefaultPublicationParser(application, httpClient, assetRetriever, null),
        )
        val manifestJson = httpClient.fetchString(HttpRequest(manifestUrl))
            .getOrElse {
                throw IllegalStateException("Failed to fetch Komga EPUB manifest: ${it.message}")
            }
        val manifestResource = StringResource(
            string = manifestJson,
            source = manifestUrl,
            properties = Resource.Properties {
                mediaType = MediaType.READIUM_WEBPUB_MANIFEST
                filename = "manifest.json"
            },
        )
        val manifestAsset = ResourceAsset(
            format = Format(
                specification = FormatSpecification(Specification.Json, Specification.Rwpm),
                mediaType = MediaType.READIUM_WEBPUB_MANIFEST,
                fileExtension = FileExtension("json"),
            ),
            resource = manifestResource,
        )

        val publication = publicationOpener.open(manifestAsset, allowUserInteraction = false)
            .getOrElse {
                manifestAsset.close()
                throw IllegalStateException(it.message)
            }
        logcat(LogPriority.DEBUG) {
            "EPUB remote open success chapterId=${request.chapterId} readingOrder=${publication.readingOrder.size} toc=${publication.tableOfContents.size}"
        }

        return EpubReaderSession(
            chapterId = request.chapterId,
            title = request.title,
            publication = publication,
            navigatorFactory = EpubNavigatorFactory(publication),
            initialLocator = initialLocator,
        )
    }
}

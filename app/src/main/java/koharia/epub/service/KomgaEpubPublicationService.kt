package koharia.epub.service

import android.app.Application
import koharia.epub.model.EpubOpenRequest
import koharia.epub.session.EpubPositionsController
import koharia.epub.session.EpubReaderSession
import logcat.LogPriority
import org.json.JSONObject
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
import java.net.URI

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
        val httpClient = httpClientFactory.create(
            sourceId = request.sourceId,
            publisherStylesOverride = request.publisherStylesOverride,
            publicationKey = request.publicationKey,
            persistCache = request.persistCache,
        )
        val assetRetriever = AssetRetriever(application.contentResolver, httpClient)
        var cachedPositions: List<Locator> = emptyList()
        val publicationOpener = PublicationOpener(
            DefaultPublicationParser(application, httpClient, assetRetriever, null),
        )
        val manifestJson = httpClient.fetchString(HttpRequest(manifestUrl))
            .getOrElse {
                throw IllegalStateException("Failed to fetch Komga EPUB manifest: ${it.message}")
            }
        cachedPositions = findPositionsUrl(manifestJson, manifestUrl.toString(), bookUrl)
            ?.let { positionsUrl ->
                httpClientFactory.cachedResource(request.sourceId, request.publicationKey, positionsUrl)
            }
            ?.toLocators()
            .orEmpty()
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

        val openedPublication = publicationOpener.open(manifestAsset, allowUserInteraction = false)
            .getOrElse {
                manifestAsset.close()
                throw IllegalStateException(it.message)
            }
        val publicationWithPositions = openedPublication.withEpubPositionsController(cachedPositions)
        val publication = publicationWithPositions.publication
        logcat(LogPriority.DEBUG) {
            "EPUB remote open success chapterId=${request.chapterId} readingOrder=${publication.readingOrder.size} toc=${publication.tableOfContents.size}"
        }

        return EpubReaderSession(
            chapterId = request.chapterId,
            title = request.title,
            publication = publication,
            navigatorFactory = EpubNavigatorFactory(publication),
            initialLocator = initialLocator,
            positionsController = publicationWithPositions.controller,
            prefetchNextResource = { locator ->
                val locatorHref = locator?.href?.toString()?.resourceKey()
                val currentIndex = publication.readingOrder.indexOfFirst { link ->
                    val linkHref = link.href.toString().resourceKey()
                    locatorHref != null &&
                        (
                            linkHref == locatorHref || linkHref.endsWith("/$locatorHref") ||
                                locatorHref.endsWith("/$linkHref")
                            )
                }.takeIf { it >= 0 } ?: 0
                publication.readingOrder.getOrNull(currentIndex + 1)
                    ?.let(publication::get)
                    ?.let { resource ->
                        try {
                            resource.read().getOrElse { ByteArray(0) }
                        } finally {
                            resource.close()
                        }
                    }
            },
        )
    }

    private fun String.resourceKey(): String =
        substringBefore('#')
            .substringBefore('?')
            .trimStart('/')

    private fun findPositionsUrl(manifestJson: String, manifestUrl: String, bookUrl: String): String? {
        val links = runCatching { JSONObject(manifestJson).optJSONArray("links") }.getOrNull()
        if (links != null) {
            var selfUrl: String? = null
            var positionsHref: String? = null
            for (index in 0 until links.length()) {
                val link = links.optJSONObject(index) ?: continue
                val href = link.optString("href").takeIf(String::isNotBlank) ?: continue
                val rel = link.opt("rel")?.toString().orEmpty()
                val type = link.optString("type")
                if (rel.contains("self")) selfUrl = href
                if (rel.contains("positions") || type.contains("position-list")) positionsHref = href
            }
            positionsHref?.let { href ->
                val base = selfUrl?.let { URI(manifestUrl).resolve(it).toString() } ?: manifestUrl
                return runCatching { URI(base).resolve(href).toString() }.getOrNull()
            }
        }
        return "$bookUrl/positions"
    }

    private fun ByteArray.toLocators(): List<Locator> = runCatching {
        val positions = JSONObject(toString(Charsets.UTF_8)).optJSONArray("positions")
            ?: return@runCatching emptyList()
        buildList {
            for (index in 0 until positions.length()) {
                Locator.fromJSON(positions.optJSONObject(index))?.let(::add)
            }
        }
    }.getOrDefault(emptyList())
}

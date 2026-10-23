package koharia.epub.service

import android.app.Application
import android.net.Uri
import koharia.epub.model.EpubOpenRequest
import koharia.epub.replacement.installTextReplacement
import koharia.epub.session.EpubPositionsController
import koharia.epub.session.EpubReaderSession
import koharia.pdf.cache.PdfReflowCacheManager
import logcat.LogPriority
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LocalEpubPublicationService(
    private val application: Application = Injekt.get(),
    private val pdfCacheManager: PdfReflowCacheManager = Injekt.get(),
) {

    suspend fun open(
        request: EpubOpenRequest,
        initialLocator: Locator?,
    ): EpubReaderSession {
        val replacements = koharia.epub.replacement.ReplacementPublication(request.replacements)
        val pdfCache = request.pdfReflow?.let { pdfCacheManager }
        val artifact = request.pdfReflow?.let {
            checkNotNull(pdfCache?.get(request.sourceId, request.chapterId, it.revision))
        }
        artifact?.let { pdfCache?.acquire(it) }
        var transferred = false
        try {
            val url = requireNotNull(Uri.parse(requireNotNull(request.localUri)).toAbsoluteUrl()) {
                "Invalid local EPUB URL"
            }
            logcat(LogPriority.DEBUG) {
                "EPUB local open start chapterId=${request.chapterId} url=$url"
            }
            val httpClient = DefaultHttpClient()
            val assetRetriever = AssetRetriever(application.contentResolver, httpClient)
            val publicationOpener = PublicationOpener(
                DefaultPublicationParser(application, httpClient, assetRetriever, null),
            )

            val asset = assetRetriever.retrieve(url, MediaType.EPUB)
                .getOrElse { throw IllegalStateException(it.message) }
            val openedPublication = publicationOpener.open(
                asset,
                allowUserInteraction = false,
                onCreatePublication = {
                    artifact?.progressive?.let { installProgressivePdf(it) }
                    installEpubXhtmlCompatibility()
                    installTextReplacement(replacements)
                },
            )
                .getOrElse {
                    asset.close()
                    throw IllegalStateException(it.message)
                }
            openedPublication.requireReadableEpub(application.stringResource(MR.strings.epub_no_readable_chapters))
            val publicationWithPositions = openedPublication.withEpubPositionsController()
            val publication = publicationWithPositions.publication
            logcat(LogPriority.DEBUG) {
                "EPUB local open success chapterId=${request.chapterId} readingOrder=${publication.readingOrder.size} toc=${publication.tableOfContents.size}"
            }

            return EpubReaderSession(
                chapterId = request.chapterId,
                title = request.title,
                publication = publication,
                navigatorFactory = EpubNavigatorFactory(publication),
                initialLocator = initialLocator,
                positionsController = publicationWithPositions.controller,
                replacements = replacements,
                pdfReflow = request.pdfReflow,
                onClosed = { artifact?.let { pdfCache?.release(it) } },
            ).also { transferred = true }
        } finally {
            if (!transferred) artifact?.let { pdfCache?.release(it) }
        }
    }
}

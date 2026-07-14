package koharia.epub.service

import koharia.epub.locator.toNavigatorLocator
import koharia.epub.model.EpubOpenRequest
import koharia.epub.model.EpubOpenRequest.OpenSource
import koharia.epub.session.EpubReaderSession
import org.readium.r2.shared.publication.Locator
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class EpubPublicationResolver(
    private val localService: LocalEpubPublicationService = Injekt.get(),
    private val komgaService: KomgaEpubPublicationService = Injekt.get(),
) {

    suspend fun open(
        request: EpubOpenRequest,
        initialLocator: Locator?,
    ): EpubReaderSession {
        val attempts = buildList {
            when (request.openSource) {
                OpenSource.LOCAL -> {
                    if (request.localUri != null) add(OpenSource.LOCAL)
                    if (request.bookUrl != null) add(OpenSource.REMOTE)
                }
                OpenSource.REMOTE -> {
                    if (request.bookUrl != null) add(OpenSource.REMOTE)
                    if (request.localUri != null) add(OpenSource.LOCAL)
                }
            }
        }.distinct()

        var lastError: Throwable? = null
        attempts.forEach { source ->
            runCatching {
                when (source) {
                    OpenSource.LOCAL -> localService.open(request, initialLocator)
                    OpenSource.REMOTE -> komgaService.open(request, initialLocator)
                }
            }
                .onSuccess { session ->
                    return session.copy(
                        initialLocator = initialLocator?.let(session.publication::toNavigatorLocator),
                    )
                }
                .onFailure { lastError = it }
        }

        throw lastError ?: IllegalStateException("No EPUB open strategy available")
    }
}

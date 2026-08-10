package koharia.epub.service

import koharia.connection.ConnectionPublicationAdapter
import koharia.connection.ConnectionSource
import koharia.epub.locator.toNavigatorLocator
import koharia.epub.model.EpubOpenRequest
import koharia.epub.model.EpubOpenRequest.OpenSource
import koharia.epub.session.EpubReaderSession
import org.readium.r2.shared.publication.Locator
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class EpubPublicationResolver(
    private val localService: LocalEpubPublicationService = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) {

    suspend fun open(
        request: EpubOpenRequest,
        initialLocator: Locator?,
    ): EpubReaderSession {
        val attempts = buildList {
            when (request.openSource) {
                OpenSource.LOCAL -> {
                    if (request.localUri != null) add(OpenSource.LOCAL)
                    if (request.remotePublication != null) add(OpenSource.REMOTE)
                }
                OpenSource.REMOTE -> {
                    if (request.remotePublication != null) add(OpenSource.REMOTE)
                    if (request.localUri != null) add(OpenSource.LOCAL)
                }
            }
        }.distinct()

        var lastError: Throwable? = null
        attempts.forEach { source ->
            runCatching {
                when (source) {
                    OpenSource.LOCAL -> localService.open(request, initialLocator)
                    OpenSource.REMOTE -> {
                        val remotePublication = requireNotNull(request.remotePublication)
                        val connectionSource = sourceManager.get(request.sourceId) as? ConnectionSource
                            ?: error("Source is not a library connection")
                        require(connectionSource.providerId == remotePublication.providerId) {
                            "Publication provider ${remotePublication.providerId} does not match " +
                                "connection provider ${connectionSource.providerId}"
                        }
                        val adapter = connectionSource as? ConnectionPublicationAdapter
                            ?: error("Connection does not support remote EPUB publications")
                        adapter.openRemotePublication(request, initialLocator)
                    }
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

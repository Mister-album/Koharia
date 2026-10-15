package koharia.komga.progress

import eu.kanade.tachiyomi.data.track.komga.KomgaApi
import koharia.connection.ConnectionIdentityChanges
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionRestoreState
import koharia.connection.ConnectionWritesPaused
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusRepository
import koharia.source.komga.KomgaProgressOwnerChanged
import koharia.source.komga.KomgaSource
import koharia.source.komga.captureKomgaProgressSession
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.domain.source.service.SourceManager
import java.io.IOException

internal class KomgaProgressWorkDriver(
    private val intents: KomgaReadStatusRepository,
    private val pages: KomgaPageEventRepository,
    private val ordering: KomgaProgressOrdering,
    private val sources: SourceManager,
    private val connections: ConnectionPreferences,
    private val identities: ConnectionIdentityChanges,
    private val api: KomgaApi,
) : KomgaProgressWorkDispatcher {
    override suspend fun dispatch(key: KomgaReadStatusKey): KomgaProgressDispatchResult {
        if (key.ownerKey.startsWith("restored:") || identities.isPaused(key.connectionId)) {
            return KomgaProgressDispatchResult.HELD
        }
        if (ConnectionRestoreState.isRestoring) throw IOException("Connection restore in progress")
        val source = sources.get(key.connectionId) as? KomgaSource ?: run {
            if (connections.getProfiles().any { it.id == key.connectionId && it.providerId == "komga" }) {
                throw IOException("Komga source is still initializing")
            }
            return KomgaProgressDispatchResult.HELD
        }
        val session = captureKomgaProgressSession(source, sources)
        if (session.account.key != key.ownerKey) throw KomgaProgressOwnerChanged()
        val checkOwner = {
            session.guard.requireCurrent()
            if (identities.isPaused(key.connectionId)) throw ConnectionWritesPaused()
            if (ConnectionRestoreState.isRestoring) throw IOException("Connection restore in progress")
        }
        checkOwner()
        val url = source.baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("api/v1/books").addPathSegment(key.bookId).build().toString()
        val transport = KomgaApiPageTransport(api, session, url) { checkOwner() }
        return KomgaProgressCoordinator(intents, pages, ordering).dispatch(key, transport, checkOwner)
    }
}

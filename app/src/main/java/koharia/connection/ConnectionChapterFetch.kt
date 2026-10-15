package koharia.connection

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.ByteBuffer
import java.security.MessageDigest

enum class ChapterFetchOrigin { OPEN, PROGRESS, EXPLICIT_REFRESH, SERVER_UPDATE, LEGACY_UPDATE }

/** Configuration binding only; it is not proof of a server's permanent account identity. */
class ConnectionChapterOwner(val key: String, val checkCurrent: () -> Unit)

interface ConnectionChapterSessionAdapter {
    fun captureChapterOwner(): ConnectionChapterOwner
}

class FetchedChapters internal constructor(
    val source: Source,
    val origin: ChapterFetchOrigin,
    val ownerKey: String,
    val chapters: List<SChapter>,
    private val validate: suspend () -> Unit,
) {
    suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        validate()
    }
}

class ChapterFetchSession private constructor(
    private val source: Source,
    private val origin: ChapterFetchOrigin,
    private val session: ConnectionSourceSession,
) {
    suspend fun fetch(block: suspend () -> List<SChapter>): FetchedChapters {
        currentCoroutineContext().ensureActive()
        session.checkCurrent()
        val chapters = block()
        currentCoroutineContext().ensureActive()
        session.checkCurrent()
        return FetchedChapters(source, origin, session.ownerKey, chapters, session::checkCurrent)
    }

    companion object {
        fun capture(
            source: Source,
            origin: ChapterFetchOrigin,
            sourceManager: SourceManager = Injekt.get(),
        ): ChapterFetchSession {
            return ChapterFetchSession(source, origin, ConnectionSourceSession.capture(source, sourceManager))
        }
    }
}

suspend fun Source.fetchChapters(
    manga: SManga,
    origin: ChapterFetchOrigin,
    forceNetwork: Boolean = false,
    sourceManager: SourceManager = Injekt.get(),
): FetchedChapters = ChapterFetchSession.capture(this, origin, sourceManager).fetch {
    (this as? ConnectionCatalogAdapter)?.getChapterList(manga, forceNetwork) ?: getChapterList(manga)
}

internal fun chapterOwnerKey(vararg values: String): String = MessageDigest.getInstance("SHA-256").run {
    values.forEach {
        val bytes = it.toByteArray(Charsets.UTF_8)
        update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        update(bytes)
    }
    digest().joinToString("") { "%02x".format(it) }
}

package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import koharia.document.DocumentEngines
import koharia.document.DocumentHeading
import koharia.document.DocumentRenderSettings
import koharia.document.DocumentSession
import koharia.document.ReflowableDocumentSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicLong

/** Adapts bitmap-oriented document engines to the existing reader pager. */
internal class DocumentPageLoader(
    context: Context,
    file: UniFile,
    private val settingsProvider: () -> DocumentRenderSettings = { DocumentRenderSettings.DEFAULT },
) : PageLoader() {
    private val lock = Any()
    private val renderLock = Any()
    private val refreshGeneration = AtomicLong(0L)
    private val context = context.applicationContext
    private val file = file
    private var session: DocumentSession = openSession()
    private var pages: List<ReaderPage> = emptyList()

    /**
     * Resolved headings, published after [warmHeadings] runs on the loader's IO coroutine.
     * Kept as a plain field (not a lazy on the session) so the [documentHeadings] getter - which
     * the UI thread reads from the toolbar click handler and the sheet composition - never has to
     * take [lock] or run the O(pages × headings) scan once warm-up has completed.
     */
    @Volatile
    private var headingsSnapshot: List<DocumentHeading>? = null

    override var isLocal: Boolean = true

    override val progressPageCount: Int
        get() = session.pageCount

    override val supportsRemoteProgress: Boolean = false

    /**
     * Document headings (level, title, pageIndex) detected at load time. Returns an empty
     * list for engines that don't extract headings (plain text, plain mobi, etc.). The binding is
     * warmed eagerly by [getPages] and [refreshPages] on the loader's IO coroutine, so this is
     * normally a field read; the lock-taking fallback only runs if a consumer reads before the
     * warm-up completes.
     */
    override val documentHeadings: List<DocumentHeading>
        get() = headingsSnapshot ?: synchronized(lock) { session.headings }

    override suspend fun getPages(): List<ReaderPage> {
        val currentSession = synchronized(lock) {
            if (pages.isEmpty()) {
                pages = createPages(session)
            }
            session
        }
        // Resolve on this IO coroutine, outside the lock (the scan is O(pages × headings) and
        // documentHeadings takes the same lock from the UI thread). Publish only if this session is
        // still the active one, so a concurrent refreshPages cannot be clobbered by a stale result.
        val resolved = currentSession.headings
        return synchronized(lock) {
            if (session === currentSession) {
                headingsSnapshot = resolved
            }
            pages
        }
    }

    override suspend fun refreshPages(): List<ReaderPage>? {
        if (isRecycled) return null
        val generation = refreshGeneration.incrementAndGet()
        val currentSession = synchronized(lock) { session }
        if (currentSession !is ReflowableDocumentSession) {
            return synchronized(lock) {
                if (isRecycled) {
                    null
                } else {
                    pages.ifEmpty { createPages(currentSession).also { pages = it } }
                }
            }
        }

        val refreshedSession = currentSession.reflow(settingsProvider())
        currentCoroutineContext().ensureActive()
        val refreshedPages = createPages(refreshedSession)
        // Resolve the refreshed session's heading binding OUTSIDE the lock: a reflow creates a
        // brand-new TextDocumentSession whose `headings` lazy would otherwise first resolve on the
        // UI thread (sheet open) after a typography/theme change, and forcing it while holding
        // `lock` would block the UI thread for the whole scan (documentHeadings takes that same
        // lock). It is published inside the commit block below so the snapshot swaps atomically
        // with `session`/`pages` — otherwise a stale-path or in-flight read could report headings
        // whose pageIndex no longer matches the pages the reader is showing.
        val refreshedHeadings = refreshedSession.headings
        val previousSession = synchronized(lock) {
            if (isRecycled || generation != refreshGeneration.get()) {
                refreshedSession.close()
                return null
            }
            val previous = session
            session = refreshedSession
            pages = refreshedPages
            headingsSnapshot = refreshedHeadings
            previous
        }
        synchronized(renderLock) {
            previousSession.close()
        }
        return refreshedPages
    }

    private fun openSession(): DocumentSession =
        DocumentEngines.open(context, file, settingsProvider())

    private fun createPages(owner: DocumentSession): List<ReaderPage> {
        return List(owner.pageCount) { index ->
            ReaderPage(index).apply {
                bitmap = { obtainBitmap(owner, index) }
                status = Page.State.Ready
            }
        }
    }

    private fun obtainBitmap(owner: DocumentSession, index: Int) = synchronized(renderLock) {
        owner.page(index).render()
    }

    override fun recycle() {
        super.recycle()
        refreshGeneration.incrementAndGet()
        synchronized(lock) {
            synchronized(renderLock) {
                session.close()
            }
        }
    }
}

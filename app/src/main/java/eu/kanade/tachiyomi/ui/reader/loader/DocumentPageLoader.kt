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

    override var isLocal: Boolean = true

    override val progressPageCount: Int
        get() = session.pageCount

    override val supportsRemoteProgress: Boolean = false

    /**
     * Document headings (level, title, pageIndex) detected at load time. Returns an empty
     * list for engines that don't extract headings (plain text, plain mobi, etc.). The session's
     * binding is triggered eagerly inside [getPages] and [refreshPages] so the O(P × H) scan runs
     * on the loader's IO coroutine, not on the UI thread the first time a consumer reads this
     * property.
     */
    override val documentHeadings: List<DocumentHeading>
        get() = synchronized(lock) { session.headings }

    override suspend fun getPages(): List<ReaderPage> {
        synchronized(lock) {
            if (pages.isEmpty()) {
                pages = createPages(session)
            }
            // Force the heading→page binding now, on the loader's IO coroutine, so the
            // first read of [documentHeadings] from the UI thread (e.g. opening the
            // HeadingListSheet) returns the cached list without scanning the pages.
            session.headings
            return pages
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
        // Warm the refreshed session's heading binding OUTSIDE the lock: a reflow creates a
        // brand-new TextDocumentSession whose `headings` lazy would otherwise first resolve on the
        // UI thread (sheet open) after a typography/theme change. Forcing it while holding `lock`
        // would instead block the UI thread for the whole scan, since documentHeadings takes that
        // same lock. `refreshedSession` is a local, so warming it here is safe.
        refreshedSession.headings
        val previousSession = synchronized(lock) {
            if (isRecycled || generation != refreshGeneration.get()) {
                refreshedSession.close()
                return null
            }
            val previous = session
            session = refreshedSession
            pages = refreshedPages
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

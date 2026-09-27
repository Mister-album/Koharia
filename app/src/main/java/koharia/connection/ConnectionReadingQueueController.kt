package koharia.connection

import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.util.system.toast
import koharia.epub.EpubReaderLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Starts each canonical chapter in its own reader context, including changes of media type. */
class ConnectionReadingQueueController(private val activity: ComponentActivity) {
    val active get() = activity.intent.hasExtra(CONTEXT)
    var position by mutableStateOf<ConnectionReadingQueuePosition?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    private val context get() = requireNotNull(activity.intent.getStringExtra(CONTEXT))
    private val sourceId get() = activity.intent.getLongExtra(SOURCE, -1)
    private val chapterUrl get() = requireNotNull(activity.intent.getStringExtra(CHAPTER))

    private suspend fun adapter(): ConnectionReadingQueueAdapter {
        val sources: SourceManager = Injekt.get()
        sources.isInitialized.first { it }
        return sources.get(sourceId) as? ConnectionReadingQueueAdapter ?: error("Reading queue unavailable")
    }

    fun start() {
        if (!active) return
        activity.lifecycleScope.launch {
            try {
                position = withContext(Dispatchers.IO) { adapter().readingQueuePosition(context, chapterUrl) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                activity.toast(MR.strings.connection_reading_queue_unavailable)
            }
        }
    }

    fun navigate(forward: Boolean, beforeLeaving: suspend () -> Unit = {}) {
        if (!active || busy) return
        val target = (if (forward) position?.next else position?.previous) ?: return
        busy = true
        activity.lifecycleScope.launch {
            try {
                val resolved = withContext(Dispatchers.IO) { adapter().resolveReadingQueueChapter(context, target) }
                beforeLeaving()
                val intent = EpubReaderLauncher().resolveIntent(activity, resolved.mangaId, resolved.chapterId)
                attach(intent, sourceId, context, resolved.chapterUrl)
                // singleTask would deliver onNewIntent to the current reader, then close it.
                val replacesSingleTask = intent.component == activity.componentName &&
                    activity.packageManager.getActivityInfo(activity.componentName, 0).launchMode ==
                    ActivityInfo.LAUNCH_SINGLE_TASK
                if (replacesSingleTask) activity.finish()
                activity.startActivity(intent)
                if (!replacesSingleTask) activity.finish()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                activity.toast(MR.strings.connection_reading_queue_unavailable)
            } finally {
                busy = false
            }
        }
    }

    fun inherit(target: Intent): Intent = if (active) attach(target, sourceId, context, chapterUrl) else target

    companion object {
        const val CONTEXT = "connection_reading_queue"
        private const val SOURCE = "connection_reading_queue_source"
        private const val CHAPTER = "connection_reading_queue_chapter"

        fun attach(intent: Intent, sourceId: Long, context: String, chapterUrl: String): Intent = intent.apply {
            putExtra(CONTEXT, context)
            putExtra(SOURCE, sourceId)
            putExtra(CHAPTER, chapterUrl)
        }
    }
}

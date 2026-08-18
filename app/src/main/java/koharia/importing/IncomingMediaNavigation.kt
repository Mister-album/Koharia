package koharia.importing

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import java.io.File
import java.net.URI

internal object IncomingMediaNavigation {
    const val ACTION_IMPORT_TEMPORARY_MEDIA = "app.koharia.action.IMPORT_TEMPORARY_MEDIA"
    const val EXTRA_TEMPORARY_MEDIA_URI = "koharia.extra.TEMPORARY_MEDIA_URI"
    const val EXTRA_EXTERNAL_MEDIA_MODE = "koharia.extra.EXTERNAL_MEDIA_MODE"
    const val EXTRA_REPLACE_EXTERNAL_OPEN_TASK = "koharia.extra.REPLACE_EXTERNAL_OPEN_TASK"
    const val EXTERNAL_MEDIA_MODE_OPEN = "open"
    const val EXTERNAL_MEDIA_MODE_IMPORT = "import"

    fun attachToReaderIntent(intent: Intent, file: File): Intent {
        intent.putExtra(EXTRA_TEMPORARY_MEDIA_URI, Uri.fromFile(file).toString())
        return intent
    }

    fun temporaryMediaUri(intent: Intent): String? =
        intent.getStringExtra(EXTRA_TEMPORARY_MEDIA_URI)

    fun inheritTemporaryMediaUri(from: Intent, target: Intent): Intent {
        temporaryMediaUri(from)?.let { uriValue ->
            target.putExtra(EXTRA_TEMPORARY_MEDIA_URI, uriValue)
        }
        return target
    }

    fun importIntent(context: Context, uriValue: String): Intent {
        return Intent(context, ExternalMediaImportActivity::class.java).apply {
            action = ACTION_IMPORT_TEMPORARY_MEDIA
            putExtra(EXTRA_TEMPORARY_MEDIA_URI, uriValue)
            putExtra(EXTRA_REPLACE_EXTERNAL_OPEN_TASK, true)
        }
    }

    fun validatedImportUri(context: Context, intent: Intent): String? {
        if (intent.action != ACTION_IMPORT_TEMPORARY_MEDIA) return null
        val value = temporaryMediaUri(intent) ?: return null
        return validatedTemporaryMediaUri(IncomingMediaSessionLocator.cacheRoot(context), value)
    }

    fun mediaUriValues(intent: Intent): List<String> {
        val uris = buildList {
            when (intent.action) {
                Intent.ACTION_VIEW -> intent.data?.let(::add)
                Intent.ACTION_SEND -> {
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                        ?.let(::addAll)
                }
            }
            intent.clipData?.let { clipData ->
                repeat(clipData.itemCount) { index ->
                    clipData.getItemAt(index).uri?.let(::add)
                }
            }
        }
        return uris.asSequence()
            .filter { uri -> uri.scheme == "content" || uri.scheme == "file" }
            .map(Uri::toString)
            .distinct()
            .toList()
    }
}

internal fun validatedTemporaryMediaUri(cacheRoot: File, uriValue: String): String? {
    val uri = runCatching { URI(uriValue) }.getOrNull() ?: return null
    if (uri.scheme != "file") return null
    val root = runCatching { cacheRoot.canonicalFile }.getOrNull() ?: return null
    val file = runCatching { File(uri).canonicalFile }.getOrNull() ?: return null
    return uriValue.takeIf { file.isFile && file.toPath().startsWith(root.toPath()) }
}

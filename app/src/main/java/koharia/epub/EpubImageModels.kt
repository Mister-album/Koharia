package koharia.epub

import android.net.Uri
import androidx.compose.runtime.Immutable
import okio.ByteString

@Immutable
data class EpubImageReference(
    val documentHref: String,
    val resourceIndex: Int,
    val currentSource: String,
    val rawSource: String,
    val altText: String?,
    val title: String?,
)

@Immutable
internal data class EpubImageContent(
    val reference: EpubImageReference,
    val bytes: ByteString,
    val mimeType: String,
    val extension: String,
    val originalFileName: String,
    val isAnimated: Boolean,
    val isSvg: Boolean,
)

@Immutable
internal data class EpubImageUiState(
    val reference: EpubImageReference? = null,
    val content: EpubImageContent? = null,
    val previewVisible: Boolean = false,
    val actionsVisible: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    val isVisible: Boolean
        get() = previewVisible || actionsVisible
}

enum class EpubImageInteraction {
    PREVIEW,
    ACTIONS,
}

internal sealed interface EpubImageEvent {
    data class Share(val uri: Uri, val mimeType: String) : EpubImageEvent

    data class Copy(val uri: Uri) : EpubImageEvent

    data class Saved(val uri: Uri) : EpubImageEvent

    data class Error(val message: String?) : EpubImageEvent
}

internal class EpubImageRequestTracker {
    private var generation = 0L

    fun next(): Long = ++generation

    fun invalidate() {
        generation += 1
    }

    fun isCurrent(request: Long): Boolean = request == generation
}

package koharia.epub

import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.text.HtmlCompat

internal data class EpubFootnoteUiState(
    val href: String,
    val contentHtml: String,
)

@Composable
internal fun EpubFootnotePopup(
    state: EpubFootnoteUiState?,
    backgroundColor: Color,
    readerFontSizeSp: Float,
    applyReaderStyles: Boolean,
    onDismissRequest: () -> Unit,
) {
    state ?: return
    val contentColor = if (backgroundColor.luminance() >= 0.45f) {
        Color(0xFF24221E)
    } else {
        Color(0xFFF1EEE7)
    }
    val widthFraction = if (applyReaderStyles) 0.78f else 0.86f
    val maxWidth = if (applyReaderStyles) 480.dp else 560.dp
    val popupWidth = minOf(LocalConfiguration.current.screenWidthDp.dp * widthFraction, maxWidth)
    val maxHeight = if (applyReaderStyles) 320.dp else 380.dp
    val horizontalPadding = if (applyReaderStyles) 14.dp else 20.dp
    val verticalPadding = if (applyReaderStyles) 12.dp else 18.dp
    val textSizeSp = if (applyReaderStyles) readerFontSizeSp else 17f

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            clippingEnabled = true,
        ),
    ) {
        Surface(
            modifier = Modifier.width(popupWidth),
            color = backgroundColor,
            contentColor = contentColor,
            shape = androidx.compose.material3.MaterialTheme.shapes.medium,
            shadowElevation = 12.dp,
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                factory = { context ->
                    TextView(context).apply {
                        includeFontPadding = false
                        setLineSpacing(0f, 1.2f)
                        movementMethod = ScrollingMovementMethod()
                        isVerticalScrollBarEnabled = true
                        linksClickable = false
                    }
                },
                update = { textView ->
                    textView.setTextColor(contentColor.toArgb())
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                    textView.text = HtmlCompat.fromHtml(
                        state.contentHtml,
                        HtmlCompat.FROM_HTML_MODE_COMPACT,
                    )
                },
            )
        }
    }
}

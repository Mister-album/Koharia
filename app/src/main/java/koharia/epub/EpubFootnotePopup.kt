package koharia.epub

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.text.HtmlCompat
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import okio.Buffer
import kotlin.math.roundToInt

internal data class EpubFootnoteUiState(
    val href: String,
    val contentHtml: String,
    val images: Map<String, EpubFootnoteImageContent> = emptyMap(),
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
    val context = LocalContext.current
    val density = LocalDensity.current
    val maxImageWidthPx = with(density) {
        (popupWidth - horizontalPadding * 2).roundToPx().coerceAtLeast(1)
    }
    val maxImageHeightPx = with(density) {
        (maxHeight - verticalPadding * 2).roundToPx().coerceAtLeast(1)
    }
    val fallbackImageSizePx = with(density) { textSizeSp.sp.roundToPx().coerceAtLeast(1) }
    val renderedContent by produceState<CharSequence>(
        initialValue = HtmlCompat.fromHtml(state.contentHtml, HtmlCompat.FROM_HTML_MODE_COMPACT),
        key1 = state,
        key2 = Triple(maxImageWidthPx, maxImageHeightPx, fallbackImageSizePx),
    ) {
        if (state.images.isEmpty()) return@produceState
        val drawables = decodeFootnoteImages(
            context = context,
            images = state.images,
            maxWidthPx = maxImageWidthPx,
            maxHeightPx = maxImageHeightPx,
            fallbackSizePx = fallbackImageSizePx,
        )
        value = HtmlCompat.fromHtml(
            state.contentHtml,
            HtmlCompat.FROM_HTML_MODE_COMPACT,
            { source -> drawables[source] },
            null,
        )
    }

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
                    textView.text = renderedContent
                },
            )
        }
    }
}

private suspend fun decodeFootnoteImages(
    context: Context,
    images: Map<String, EpubFootnoteImageContent>,
    maxWidthPx: Int,
    maxHeightPx: Int,
    fallbackSizePx: Int,
): Map<String, Drawable> = buildMap {
    images.forEach { (sourceName, content) ->
        val source = Buffer().write(content.bytes)
        val drawable = try {
            val request = ImageRequest.Builder(context)
                .data(source)
                .size(maxWidthPx, maxHeightPx)
                .crossfade(false)
                .apply {
                    if (content.isSvg) decoderFactory(SvgDecoder.Factory())
                }
                .build()
            context.imageLoader.execute(request).image?.asDrawable(context.resources)
        } finally {
            source.close()
        } ?: return@forEach
        drawable.setFootnoteBounds(maxWidthPx, maxHeightPx, fallbackSizePx)
        put(sourceName, drawable)
    }
}

private fun Drawable.setFootnoteBounds(maxWidthPx: Int, maxHeightPx: Int, fallbackSizePx: Int) {
    val sourceWidth = intrinsicWidth.takeIf { it > 0 } ?: fallbackSizePx
    val sourceHeight = intrinsicHeight.takeIf { it > 0 } ?: fallbackSizePx
    val scale = minOf(
        1f,
        maxWidthPx.toFloat() / sourceWidth,
        maxHeightPx.toFloat() / sourceHeight,
    )
    setBounds(
        0,
        0,
        (sourceWidth * scale).roundToInt().coerceAtLeast(1),
        (sourceHeight * scale).roundToInt().coerceAtLeast(1),
    )
}

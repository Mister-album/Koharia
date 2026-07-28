package koharia.epub

import android.content.Context
import android.graphics.Typeface
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
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
    val anchorXFraction: Float? = null,
    val anchorYFraction: Float? = null,
    val images: Map<String, EpubFootnoteImageContent> = emptyMap(),
)

@Composable
internal fun EpubFootnotePopup(
    state: EpubFootnoteUiState?,
    backgroundColor: Color,
    readerFontSizeSp: Float,
    applyReaderStyles: Boolean,
    typeface: Typeface?,
    onDismissRequest: () -> Unit,
) {
    state ?: return
    val contentColor = if (backgroundColor.luminance() >= 0.45f) {
        Color(0xFF24221E)
    } else {
        Color(0xFFF1EEE7)
    }
    val configuration = LocalConfiguration.current
    val widthFraction = if (applyReaderStyles) 0.78f else 0.86f
    val maxWidth = if (applyReaderStyles) 480.dp else 560.dp
    val popupWidth = minOf(configuration.screenWidthDp.dp * widthFraction, maxWidth)
    val preferredMaxHeight = if (applyReaderStyles) 320.dp else 380.dp
    val horizontalPadding = if (applyReaderStyles) 14.dp else 20.dp
    val verticalPadding = if (applyReaderStyles) 12.dp else 18.dp
    val textSizeSp = if (applyReaderStyles) readerFontSizeSp else 17f
    val context = LocalContext.current
    val density = LocalDensity.current
    val anchorYFraction = state.anchorYFraction?.coerceIn(0f, 1f) ?: 0.5f
    val edgeMarginPx = with(density) { FOOTNOTE_POPUP_EDGE_MARGIN_DP.dp.roundToPx() }
    val anchorGapPx = with(density) { FOOTNOTE_POPUP_ANCHOR_GAP_DP.dp.roundToPx() }
    val anchorLineRadiusPx = with(density) {
        (textSizeSp.sp.toPx() * FOOTNOTE_ANCHOR_LINE_RADIUS_MULTIPLIER).roundToInt()
    }
    val popupVerticalReserveDp = with(density) {
        (edgeMarginPx + anchorGapPx + anchorLineRadiusPx).toDp()
    }
    val availableSideHeight = (
        maxOf(anchorYFraction, 1f - anchorYFraction) * configuration.screenHeightDp
        ).dp - popupVerticalReserveDp
    val maxHeight = minOf(
        preferredMaxHeight,
        availableSideHeight.coerceAtLeast(FOOTNOTE_POPUP_MIN_HEIGHT_DP.dp),
    )
    val positionProvider = remember(
        state.href,
        state.anchorXFraction,
        state.anchorYFraction,
        edgeMarginPx,
        anchorGapPx,
        anchorLineRadiusPx,
    ) {
        EpubFootnotePopupPositionProvider(
            anchorXFraction = state.anchorXFraction ?: 0.5f,
            anchorYFraction = anchorYFraction,
            edgeMarginPx = edgeMarginPx,
            anchorGapPx = anchorGapPx,
            anchorLineRadiusPx = anchorLineRadiusPx,
        )
    }
    val placement = positionProvider.placement
    val bubbleShape = remember(placement) { EpubFootnoteBubbleShape(placement) }
    val tailHeight = FOOTNOTE_BUBBLE_TAIL_HEIGHT_DP.dp
    val contentTopPadding = verticalPadding + if (placement.isAboveAnchor) 0.dp else tailHeight
    val contentBottomPadding = verticalPadding + if (placement.isAboveAnchor) tailHeight else 0.dp
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
        popupPositionProvider = positionProvider,
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
            shape = bubbleShape,
            shadowElevation = 12.dp,
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .padding(
                        start = horizontalPadding,
                        top = contentTopPadding,
                        end = horizontalPadding,
                        bottom = contentBottomPadding,
                    ),
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
                    textView.typeface = typeface ?: Typeface.DEFAULT
                    textView.text = renderedContent
                },
            )
        }
    }
}

private data class EpubFootnotePopupPlacement(
    val isAboveAnchor: Boolean,
    val tailCenterPx: Int,
)

private class EpubFootnotePopupPositionProvider(
    anchorXFraction: Float,
    anchorYFraction: Float,
    private val edgeMarginPx: Int,
    private val anchorGapPx: Int,
    private val anchorLineRadiusPx: Int,
) : PopupPositionProvider {

    private val anchorXFraction = anchorXFraction.coerceIn(0f, 1f)
    private val anchorYFraction = anchorYFraction.coerceIn(0f, 1f)

    var placement by mutableStateOf(
        EpubFootnotePopupPlacement(
            isAboveAnchor = anchorYFraction >= 0.5f,
            tailCenterPx = 0,
        ),
    )
        private set

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchorX = (windowSize.width * anchorXFraction).roundToInt()
        val anchorY = (windowSize.height * anchorYFraction).roundToInt()
        val anchorTop = anchorY - anchorLineRadiusPx
        val anchorBottom = anchorY + anchorLineRadiusPx
        val availableAbove = anchorTop - anchorGapPx - edgeMarginPx
        val availableBelow = windowSize.height - anchorBottom - anchorGapPx - edgeMarginPx
        val fitsAbove = popupContentSize.height <= availableAbove
        val fitsBelow = popupContentSize.height <= availableBelow
        val isAboveAnchor = when {
            fitsAbove -> true
            fitsBelow -> false
            else -> availableAbove >= availableBelow
        }

        val preferredX = anchorX - popupContentSize.width / 2
        val popupX = preferredX.coerceToWindow(
            contentSize = popupContentSize.width,
            windowSize = windowSize.width,
            edgeMargin = edgeMarginPx,
        )
        val preferredY = if (isAboveAnchor) {
            anchorTop - anchorGapPx - popupContentSize.height
        } else {
            anchorBottom + anchorGapPx
        }
        val popupY = preferredY.coerceToWindow(
            contentSize = popupContentSize.height,
            windowSize = windowSize.height,
            edgeMargin = edgeMarginPx,
        )
        val updatedPlacement = EpubFootnotePopupPlacement(
            isAboveAnchor = isAboveAnchor,
            tailCenterPx = (anchorX - popupX).coerceIn(0, popupContentSize.width),
        )
        if (placement != updatedPlacement) {
            placement = updatedPlacement
        }
        return IntOffset(popupX, popupY)
    }
}

private class EpubFootnoteBubbleShape(
    private val placement: EpubFootnotePopupPlacement,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val tailHeight = with(density) { FOOTNOTE_BUBBLE_TAIL_HEIGHT_DP.dp.toPx() }
        val tailHalfWidth = with(density) { FOOTNOTE_BUBBLE_TAIL_HALF_WIDTH_DP.dp.toPx() }
        val cornerRadius = with(density) { FOOTNOTE_BUBBLE_CORNER_RADIUS_DP.dp.toPx() }
        val bodyTop = if (placement.isAboveAnchor) 0f else tailHeight
        val bodyBottom = if (placement.isAboveAnchor) size.height - tailHeight else size.height
        val tailCenter = placement.tailCenterPx.toFloat().coerceIn(
            cornerRadius + tailHalfWidth,
            (size.width - cornerRadius - tailHalfWidth).coerceAtLeast(cornerRadius + tailHalfWidth),
        )
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(0f, bodyTop, size.width, bodyBottom),
                    cornerRadius = CornerRadius(cornerRadius),
                ),
            )
            if (placement.isAboveAnchor) {
                moveTo(tailCenter - tailHalfWidth, bodyBottom)
                lineTo(tailCenter, size.height)
                lineTo(tailCenter + tailHalfWidth, bodyBottom)
            } else {
                moveTo(tailCenter - tailHalfWidth, bodyTop)
                lineTo(tailCenter, 0f)
                lineTo(tailCenter + tailHalfWidth, bodyTop)
            }
            close()
        }
        return Outline.Generic(path)
    }
}

private fun Int.coerceToWindow(contentSize: Int, windowSize: Int, edgeMargin: Int): Int {
    val maximum = (windowSize - contentSize - edgeMargin).coerceAtLeast(0)
    val minimum = edgeMargin.coerceAtMost(maximum)
    return coerceIn(minimum, maximum)
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

private const val FOOTNOTE_POPUP_EDGE_MARGIN_DP = 8
private const val FOOTNOTE_POPUP_ANCHOR_GAP_DP = 2
private const val FOOTNOTE_ANCHOR_LINE_RADIUS_MULTIPLIER = 0.7f
private const val FOOTNOTE_POPUP_MIN_HEIGHT_DP = 96
private const val FOOTNOTE_BUBBLE_TAIL_HEIGHT_DP = 8
private const val FOOTNOTE_BUBBLE_TAIL_HALF_WIDTH_DP = 7
private const val FOOTNOTE_BUBBLE_CORNER_RADIUS_DP = 12

package eu.kanade.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/** Shrinks button spacing evenly, without scaling icons or changing action order. */
@Composable
internal fun AdaptiveAppBarActions(
    reservedWidth: Int,
    content: @Composable () -> Unit,
) {
    Layout(content = content) { measurables, constraints ->
        val preferredWidths = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val widths = appBarActionWidths(
            preferredWidths = preferredWidths,
            availableWidth = (constraints.maxWidth - reservedWidth).coerceAtLeast(0),
            minimumButtonWidth = 40.dp.roundToPx(),
            regularButtonWidth = 48.dp.roundToPx(),
        )
        var remainingWidth = constraints.maxWidth
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(
                constraints.copy(minWidth = 0, maxWidth = minOf(widths[index], remainingWidth)),
            ).also { remainingWidth -= it.width }
        }
        val width = placeables.sumOf { it.width }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            var x = 0
            placeables.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width
            }
        }
    }
}

internal fun appBarActionWidths(
    preferredWidths: List<Int>,
    availableWidth: Int,
    minimumButtonWidth: Int,
    regularButtonWidth: Int,
): List<Int> {
    val minimumWidths = preferredWidths.map { width ->
        if (width <= regularButtonWidth) minOf(width, minimumButtonWidth) else width
    }
    val reducible = preferredWidths.zip(minimumWidths).sumOf { (preferred, minimum) -> preferred - minimum }
    if (reducible == 0) return preferredWidths
    val reduction = (preferredWidths.sum() - availableWidth).coerceIn(0, reducible)
    var remainder = 0
    return preferredWidths.mapIndexed { index, preferred ->
        val numerator = (preferred - minimumWidths[index]) * reduction + remainder
        remainder = numerator % reducible
        preferred - numerator / reducible
    }
}

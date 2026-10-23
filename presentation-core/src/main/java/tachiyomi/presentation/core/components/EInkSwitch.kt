package tachiyomi.presentation.core.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import tachiyomi.presentation.core.motion.LocalEInkDisplayPolicy

/** Keeps the Material switch shape without starting thumb, color or ripple animations on E-Ink. */
@Composable
fun EInkSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!LocalEInkDisplayPolicy.current.enabled) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
        return
    }
    val colors = SwitchDefaults.colors()
    val surface = MaterialTheme.colorScheme.surface
    val track = when {
        !enabled && checked -> colors.disabledCheckedTrackColor
        !enabled -> colors.disabledUncheckedTrackColor
        checked -> colors.checkedTrackColor
        else -> colors.uncheckedTrackColor
    }.compositeOver(surface)
    val border = when {
        !enabled && checked -> colors.disabledCheckedBorderColor
        !enabled -> colors.disabledUncheckedBorderColor
        checked -> colors.checkedBorderColor
        else -> colors.uncheckedBorderColor
    }
    val thumb = when {
        !enabled && checked -> colors.disabledCheckedThumbColor
        !enabled -> colors.disabledUncheckedThumbColor
        checked -> colors.checkedThumbColor
        else -> colors.uncheckedThumbColor
    }.compositeOver(track)
    val interactive = if (onCheckedChange != null) {
        Modifier.minimumInteractiveComponentSize().toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            interactionSource = null,
            indication = null,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Canvas(modifier.then(interactive).wrapContentSize().requiredSize(52.dp, 32.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color = border,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius((size.height - stroke) / 2),
            style = Stroke(stroke),
        )
        val leftToRight = layoutDirection == LayoutDirection.Ltr
        val centerX = if (checked == leftToRight) size.width - size.height / 2 else size.height / 2
        drawCircle(
            color = thumb,
            radius = (if (checked) 12.dp else 8.dp).toPx(),
            center = Offset(centerX, size.height / 2),
        )
    }
}

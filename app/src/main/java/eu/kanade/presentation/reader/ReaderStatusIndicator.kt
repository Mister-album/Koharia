package eu.kanade.presentation.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.ui.reader.loader.ReaderBufferingState
import eu.kanade.tachiyomi.ui.reader.setting.ReaderStatusPosition
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.motion.EInkAnimatedVisibility
import java.util.Date

internal data class ReaderBatteryState(val percent: Int, val charging: Boolean)

internal fun formatReaderTransferSpeed(bytesPerSecond: Long): String {
    val value = bytesPerSecond.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024.0 * 1024.0 -> "%.1f MB/s".format(value / (1024.0 * 1024.0))
        value >= 1024.0 -> "%.0f KB/s".format(value / 1024.0)
        else -> "${value.toLong()} B/s"
    }
}

@Composable
fun ReaderStatusIndicator(
    chapterTitle: String?,
    currentPage: Int,
    totalPages: Int,
    visiblePageStart: Int = currentPage,
    percentageOnly: Boolean = false,
    showStatus: Boolean,
    showChapterTitle: Boolean = true,
    showClock: Boolean = true,
    showBattery: Boolean = true,
    showPages: Boolean = true,
    position: ReaderStatusPosition = ReaderStatusPosition.BOTTOM,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
    edgeBandHeight: androidx.compose.ui.unit.Dp? = null,
    edgeInsets: WindowInsets? = null,
) {
    if (!showStatus) return
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    var battery by remember { mutableStateOf(context.currentBatteryState()) }

    LaunchedEffect(showStatus, showClock) {
        if (!showStatus || !showClock) return@LaunchedEffect
        while (true) {
            val wait = 60_000L - System.currentTimeMillis() % 60_000L
            delay(wait.coerceAtLeast(1_000L))
            now = Date()
        }
    }
    DisposableEffect(showStatus, showBattery) {
        if (!showStatus || !showBattery) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                battery = intent.toReaderBatteryState()
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val pageText = if (showPages) {
        readerPageIndicatorText(currentPage, totalPages, visiblePageStart, percentageOnly)
    } else {
        null
    }
    val statusColor = contentColor.copy(alpha = 0.62f)
    val textStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
        shadow = Shadow(
            color = if (contentColor == Color.White) Color.Black.copy(alpha = 0.5f) else Color.Transparent,
            blurRadius = 3f,
        ),
    )

    val statusInsets = edgeInsets ?: if (position ==
        ReaderStatusPosition.TOP
    ) {
        WindowInsets.statusBars
    } else {
        WindowInsets.navigationBars
    }
    ReaderStatusLayer(modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .align(if (position == ReaderStatusPosition.TOP) Alignment.TopCenter else Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        statusInsets.only(
                            if (position ==
                                ReaderStatusPosition.TOP
                            ) {
                                WindowInsetsSides.Top
                            } else {
                                WindowInsetsSides.Bottom
                            },
                        ),
                    )
                    .then(
                        if (edgeBandHeight != null) Modifier.height(edgeBandHeight) else Modifier,
                    )
                    .padding(horizontal = 18.dp, vertical = if (edgeBandHeight == null) 10.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(0.5f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showChapterTitle && !chapterTitle.isNullOrBlank()) {
                        Text(
                            text = chapterTitle,
                            modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                            style = textStyle,
                            color = statusColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (pageText != null) {
                        Text(
                            text = pageText,
                            modifier = Modifier.alignByBaseline(),
                            style = textStyle,
                            color = statusColor,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (showClock || showBattery) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showClock) {
                            Text(
                                DateFormat.getTimeFormat(context).format(now),
                                style = textStyle,
                                color = statusColor,
                                maxLines = 1,
                            )
                        }
                        if (showBattery) {
                            battery?.let { value ->
                                ReaderBatteryIcon(value, statusColor)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderBatteryIcon(battery: ReaderBatteryState, color: Color) {
    val label =
        stringResource(
            if (battery.charging) MR.strings.reader_battery_charging else MR.strings.reader_battery_level,
            battery.percent,
        )
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(width = 24.dp, height = 12.dp)) {
                val stroke = 1.dp.toPx()
                val bodyWidth = size.width - 3.dp.toPx()
                drawRoundRect(
                    color,
                    Offset(stroke / 2, stroke / 2),
                    Size(bodyWidth - stroke, size.height - stroke),
                    CornerRadius(2.dp.toPx()),
                    style = Stroke(stroke),
                )
                drawRoundRect(
                    color,
                    Offset(bodyWidth + 1.dp.toPx(), size.height * 0.3f),
                    Size(2.dp.toPx(), size.height * 0.4f),
                    CornerRadius(0.7.dp.toPx()),
                )
                val inset = 2.5.dp.toPx()
                val fillWidth = (bodyWidth - inset * 2) * battery.percent.coerceIn(0, 100) / 100f
                if (fillWidth > 0) {
                    drawRoundRect(
                        color.copy(alpha = color.alpha * 0.75f),
                        Offset(inset, inset),
                        Size(fillWidth, size.height - inset * 2),
                        CornerRadius(0.8.dp.toPx()),
                    )
                }
            }
            if (battery.charging) {
                Icon(
                    Icons.Outlined.Bolt,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

internal fun readerStatusLeftText(
    chapterTitle: String?,
    pageText: String?,
    showChapterTitle: Boolean,
): String = listOfNotNull(
    chapterTitle?.takeIf { showChapterTitle && it.isNotBlank() },
    pageText,
).joinToString(" · ")

@Composable
fun ReaderBufferingIndicator(
    buffering: ReaderBufferingState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!enabled || !buffering.isBuffering) return
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        StatusSurface(modifier = Modifier.offset(y = 40.dp)) {
            StatusText(readerBufferingText(buffering, enabled))
        }
    }
}

@Composable
internal fun readerBufferingText(buffering: ReaderBufferingState, enabled: Boolean): String {
    val speed = buffering.visibleSpeed(enabled)
    return if (speed != null) {
        stringResource(MR.strings.reader_buffering_speed, formatReaderTransferSpeed(speed))
    } else {
        stringResource(MR.strings.reader_buffering_waiting)
    }
}

internal fun ReaderBufferingState.visibleSpeed(enabled: Boolean): Long? =
    bytesPerSecond.takeIf { enabled && isBuffering && it > 0L }

@Composable
private fun StatusSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Black.copy(alpha = 0.32f),
        contentColor = Color.White.copy(alpha = 0.9f),
    ) {
        Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) { content() }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text = text, fontSize = 11.sp, maxLines = 1)
}

private fun Context.currentBatteryState(): ReaderBatteryState? =
    registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.toReaderBatteryState()

private fun Intent.toReaderBatteryState(): ReaderBatteryState? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    val status = getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    return ReaderBatteryState(
        percent = (level * 100 / scale).coerceIn(0, 100),
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL,
    )
}

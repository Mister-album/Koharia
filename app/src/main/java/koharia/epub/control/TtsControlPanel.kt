package koharia.epub.control

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import koharia.tts.TtsAction
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.motion.EInkAnimatedVisibility
import kotlin.math.roundToInt

/**
 * Phase 3.3：阅读器内朗读控制 pill —— `Prev / Play-Pause / Next` 三键。
 *
 * 设计要点：
 * - **只渲染，不调度**：本 Composable 不直接调用 `TtsService.dispatch`；通过 [onAction]
 *   把决策权交回 [koharia.epub.EpubReaderViewModel]，让 VM 持有 `application` 并避免 Composable
 *   持有 [android.content.Context]。
 * - **动画收尾**：`EInkAnimatedVisibility` 用 `slideInVertically + fadeIn`（从屏幕底滑入），
 *   与现有 `EpubReaderBottomArea` 的滑动动画一致。**必须走 `presentation-core` 的 E-Ink
 *   包装层**（`verifyEInkMotion` 会拒绝直接 import `AnimatedVisibility`）：E-Ink 模式下
 *   包装层把 enter/exit 换成 `EnterTransition.None`，避免墨屏整屏刷新。
 *   v0.4.2-65 修正 —— 此前直接用了 `AnimatedVisibility`，因为 `verifyEInkMotion` 门禁
 *   自身受 configuration cache 影响从未真正执行过，违规才得以蒙混进来。
 * - **`derivedStateOf` 复用**：`shouldRender` 是状态读取，`remember { derivedStateOf ... }`
 *   保证 [panelState] 变化时才触发重组。
 * - **A11y**：每个按钮的 `contentDescription` 用 `stringResource(MR.strings.tts_action_*)`，
 *   TalkBack 可读出"上一句/播放/暂停/下一句"。
 *
 * 调用方只需：
 * ```
 * TtsControlPanel(
 *     panelState = TtsPanelState(active = state.ttsActive, playbackState = state.ttsPlaybackState),
 *     onAction = viewModel::onTtsAction,
 *     modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
 * )
 * ```
 */
@Composable
fun TtsControlPanel(
    panelState: TtsPanelState,
    onAction: (TtsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible by remember(panelState) {
        derivedStateOf { panelState.shouldRender }
    }

    // Phase 3 step 3 真机反馈增强：用户可拖动 pill 到任意位置。
    // - `rememberSaveable` 持久化 offset,横竖屏切换 / 进程被杀后重启都保留
    // - `detectDragGestures` 只在 touchSlop 之后才触发,不会和按钮单击冲突
    // - `change.consume()` 让 pill 抢在 WebView 滚动之前消费拖动手势(避免误触翻页)
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }

    EInkAnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = tween(durationMillis = 220),
            initialOffsetY = { it },
        ) + fadeIn(animationSpec = tween(durationMillis = 180)),
        exit = slideOutVertically(
            animationSpec = tween(durationMillis = 180),
            targetOffsetY = { it },
        ) + fadeOut(animationSpec = tween(durationMillis = 140)),
        modifier = modifier,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            // 65% 透明 surfaceContainerHigh: 阅读器背景透出来但仍有 surface 色调;
            // contentColor 由 Surface 自动根据底色算对比度。
            // 调高 alpha 会显得太实,调低(<0.5)文字在亮背景上偏弱。
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
            tonalElevation = 6.dp,
            shadowElevation = 4.dp, // 降低:透明 surface 的阴影会显得突兀
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        },
                    )
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PillIconButton(
                    icon = Icons.Outlined.SkipPrevious,
                    contentDescription = stringResource(MR.strings.tts_action_prev),
                    onClick = { onAction(TtsAction.PREV) },
                    primary = false,
                )
                PillIconButton(
                    icon = if (panelState.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(
                        if (panelState.isPlaying) {
                            MR.strings.tts_action_pause
                        } else {
                            MR.strings.tts_action_play
                        },
                    ),
                    onClick = { onAction(panelState.playPauseAction) },
                    primary = true,
                )
                PillIconButton(
                    icon = Icons.Outlined.SkipNext,
                    contentDescription = stringResource(MR.strings.tts_action_next),
                    onClick = { onAction(TtsAction.NEXT) },
                    primary = false,
                )
            }
        }
    }
}

@Composable
private fun PillIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    primary: Boolean,
) {
    if (primary) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(icon, contentDescription = contentDescription)
        }
    } else {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(icon, contentDescription = contentDescription)
        }
    }
}

package tachiyomi.presentation.core.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tachiyomi.presentation.core.motion.LocalEInkDisplayPolicy
import androidx.compose.material3.CircularProgressIndicator as MaterialCircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator as MaterialLinearProgressIndicator

/**
 * A combined [MaterialCircularProgressIndicator] that always rotates.
 *
 * By always rotating we give the feedback to the user that the application isn't 'stuck'.
 */
@Composable
fun CombinedCircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    if (LocalEInkDisplayPolicy.current.enabled) {
        val currentProgress = progress()
        if (currentProgress == 0f) {
            EInkCircularProgressIndicator(modifier = modifier)
        } else {
            EInkCircularProgressIndicator(progress = { currentProgress }, modifier = modifier)
        }
        return
    }
    AnimatedContent(
        targetState = progress() == 0f,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "progressState",
        modifier = modifier,
    ) { indeterminate ->
        if (indeterminate) {
            // Indeterminate
            MaterialCircularProgressIndicator()
        } else {
            // Determinate
            val infiniteTransition = rememberInfiniteTransition(label = "infiniteRotation")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "rotation",
            )
            val animatedProgress by animateFloatAsState(
                targetValue = progress(),
                animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                label = "progress",
            )
            MaterialCircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

@Composable
fun EInkCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.circularColor,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
    trackColor: Color = ProgressIndicatorDefaults.circularIndeterminateTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.CircularIndeterminateStrokeCap,
    gapSize: Dp = ProgressIndicatorDefaults.CircularIndicatorTrackGapSize,
) {
    if (LocalEInkDisplayPolicy.current.enabled) {
        Box(
            modifier = modifier
                .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
                .progressSemantics(),
        ) {
            MaterialCircularProgressIndicator(
                progress = { STATIC_PROGRESS },
                modifier = Modifier
                    .matchParentSize()
                    .clearAndSetSemantics {},
                color = color,
                strokeWidth = strokeWidth,
                trackColor = trackColor,
                strokeCap = strokeCap,
                gapSize = gapSize,
            )
        }
    } else {
        MaterialCircularProgressIndicator(
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth,
            trackColor = trackColor,
            strokeCap = strokeCap,
            gapSize = gapSize,
        )
    }
}

@Composable
fun EInkLinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = ProgressIndicatorDefaults.linearTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
    gapSize: Dp = ProgressIndicatorDefaults.LinearIndicatorTrackGapSize,
) {
    if (LocalEInkDisplayPolicy.current.enabled) {
        Box(
            modifier = modifier
                .defaultMinSize(minWidth = 240.dp, minHeight = 4.dp)
                .progressSemantics(),
        ) {
            MaterialLinearProgressIndicator(
                progress = { STATIC_PROGRESS },
                modifier = Modifier
                    .matchParentSize()
                    .clearAndSetSemantics {},
                color = color,
                trackColor = trackColor,
                strokeCap = strokeCap,
                gapSize = gapSize,
            )
        }
    } else {
        MaterialLinearProgressIndicator(
            modifier = modifier,
            color = color,
            trackColor = trackColor,
            strokeCap = strokeCap,
            gapSize = gapSize,
        )
    }
}

@Composable
fun EInkLinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = ProgressIndicatorDefaults.linearTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
    gapSize: Dp = ProgressIndicatorDefaults.LinearIndicatorTrackGapSize,
) {
    MaterialLinearProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        strokeCap = strokeCap,
        gapSize = gapSize,
    )
}

@Composable
fun EInkCircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.circularColor,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
    trackColor: Color = ProgressIndicatorDefaults.circularDeterminateTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.CircularDeterminateStrokeCap,
    gapSize: Dp = ProgressIndicatorDefaults.CircularIndicatorTrackGapSize,
) {
    MaterialCircularProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        strokeWidth = strokeWidth,
        trackColor = trackColor,
        strokeCap = strokeCap,
        gapSize = gapSize,
    )
}

private const val STATIC_PROGRESS = 0.75f

@Preview
@Composable
private fun CombinedCircularProgressIndicatorPreview() {
    var progress by remember { mutableFloatStateOf(0f) }
    MaterialTheme {
        Scaffold(
            bottomBar = {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        progress = when (progress) {
                            0f -> 0.15f
                            0.15f -> 0.25f
                            0.25f -> 0.5f
                            0.5f -> 0.75f
                            0.75f -> 0.95f
                            else -> 0f
                        }
                    },
                ) {
                    Text("change")
                }
            },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(it),
            ) {
                CombinedCircularProgressIndicator(progress = { progress })
            }
        }
    }
}

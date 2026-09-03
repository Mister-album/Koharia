package tachiyomi.presentation.core.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.IntSize

object EInkMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
}

@Composable
fun ProvideEInkDisplayPolicy(
    policy: EInkDisplayPolicy,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalEInkDisplayPolicy provides policy, content = content)
}

@Composable
fun <T> eInkAnimationSpec(default: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> {
    return if (LocalEInkDisplayPolicy.current.enabled) snap() else default
}

@Composable
fun Modifier.eInkAnimateContentSize(
    animationSpec: FiniteAnimationSpec<IntSize> = spring(),
    alignment: Alignment = Alignment.TopStart,
    finishedListener: ((initialValue: IntSize, targetValue: IntSize) -> Unit)? = null,
): Modifier {
    return if (LocalEInkDisplayPolicy.current.enabled) {
        this
    } else {
        animateContentSize(animationSpec, alignment, finishedListener)
    }
}

@Composable
fun rememberEInkAwareAnimatedVectorPainter(
    image: AnimatedImageVector,
    atEnd: Boolean,
    staticImage: ImageVector,
): Painter {
    return if (LocalEInkDisplayPolicy.current.enabled) {
        rememberVectorPainter(staticImage)
    } else {
        rememberAnimatedVectorPainter(image, atEnd)
    }
}

@Composable
fun EInkAnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn() + expandIn(),
    exit: ExitTransition = shrinkOut() + fadeOut(),
    label: String = "AnimatedVisibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val eInkEnabled = LocalEInkDisplayPolicy.current.enabled
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (eInkEnabled) EnterTransition.None else enter,
        exit = if (eInkEnabled) ExitTransition.None else exit,
        label = label,
        content = content,
    )
}

@Composable
fun <S> EInkAnimatedContent(
    targetState: S,
    transitionSpec: AnimatedContentTransitionScope<S>.() -> ContentTransform,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    label: String = "AnimatedContent",
    contentKey: (targetState: S) -> Any? = { it },
    content: @Composable AnimatedContentScope.(targetState: S) -> Unit,
) {
    val eInkEnabled = LocalEInkDisplayPolicy.current.enabled
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = if (eInkEnabled) {
            { EnterTransition.None togetherWith ExitTransition.None }
        } else {
            transitionSpec
        },
        contentAlignment = contentAlignment,
        label = label,
        contentKey = contentKey,
        content = content,
    )
}

@Composable
fun EInkStaticProgressIndicator(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        content()
    }
}

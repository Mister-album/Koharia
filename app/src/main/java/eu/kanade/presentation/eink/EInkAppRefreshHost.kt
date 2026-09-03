package eu.kanade.presentation.eink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import eu.kanade.domain.ui.EInkPreferences
import kotlinx.coroutines.delay
import tachiyomi.presentation.core.motion.EInkRefreshScheduler
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.milliseconds

enum class EInkRefreshReason {
    ACTIVITY,
    ROUTE,
    TAB,
}

@Stable
class EInkAppRefreshController(
    private val preferences: EInkPreferences,
) {
    private val scheduler = EInkRefreshScheduler(preferences.appRefreshInterval.get())

    internal var generation by mutableLongStateOf(0L)
        private set

    fun request(reason: EInkRefreshReason, key: String) {
        if (!preferences.enabled.get() || !preferences.appRefreshEnabled.get()) return
        if (scheduler.request("${reason.name}:$key") == EInkRefreshScheduler.RequestResult.START) {
            generation += 1
        }
    }

    internal fun finish() {
        scheduler.finish()
    }

    internal fun setInterval(interval: Int) {
        scheduler.setInterval(interval)
    }
}

val LocalEInkAppRefreshController: ProvidableCompositionLocal<EInkAppRefreshController?> =
    staticCompositionLocalOf { null }

@Composable
fun EInkAppRefreshRoot(
    initialKey: String?,
    content: @Composable () -> Unit,
) {
    val preferences = remember { Injekt.get<EInkPreferences>() }
    val controller = remember { EInkAppRefreshController(preferences) }
    val eInkEnabled by preferences.enabled.collectAsState()
    val refreshEnabled by preferences.appRefreshEnabled.collectAsState()
    val refreshInterval by preferences.appRefreshInterval.collectAsState()
    val refreshDuration by preferences.appRefreshDurationMillis.collectAsState()
    val refreshColor by preferences.appRefreshColor.collectAsState()

    LaunchedEffect(eInkEnabled, refreshEnabled, refreshInterval) {
        controller.setInterval(refreshInterval)
        if (eInkEnabled && refreshEnabled && initialKey != null) {
            controller.request(EInkRefreshReason.ACTIVITY, initialKey)
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalEInkAppRefreshController provides controller) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            EInkAppRefreshOverlay(
                controller = controller,
                enabled = eInkEnabled && refreshEnabled,
                durationMillis = refreshDuration,
                color = refreshColor,
            )
        }
    }
}

@Composable
private fun EInkAppRefreshOverlay(
    controller: EInkAppRefreshController,
    enabled: Boolean,
    durationMillis: Int,
    color: EInkPreferences.RefreshColor,
) {
    val generation = controller.generation
    var currentColor by remember { androidx.compose.runtime.mutableStateOf<Color?>(null) }

    LaunchedEffect(generation, enabled) {
        if (!enabled || generation == 0L) {
            currentColor = null
            return@LaunchedEffect
        }

        val halfDuration = durationMillis.coerceIn(
            EInkPreferences.MIN_REFRESH_DURATION_MILLIS,
            EInkPreferences.MAX_REFRESH_DURATION_MILLIS,
        ).milliseconds / 2
        currentColor = if (color == EInkPreferences.RefreshColor.BLACK) Color.Black else Color.White
        delay(halfDuration)
        if (color == EInkPreferences.RefreshColor.WHITE_BLACK) currentColor = Color.Black
        delay(halfDuration)
        currentColor = null
        controller.finish()
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(Float.MAX_VALUE),
    ) {
        currentColor?.let(::drawRect)
    }
}

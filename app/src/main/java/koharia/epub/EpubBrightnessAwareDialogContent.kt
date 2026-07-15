package koharia.epub

import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import eu.kanade.presentation.reader.ReaderContentOverlay

@Composable
internal fun EpubBrightnessAwareDialogContent(
    brightnessState: EpubBrightnessState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window

    LaunchedEffect(dialogWindow, brightnessState.windowBrightness) {
        dialogWindow?.let { window ->
            window.attributes = window.attributes.apply {
                screenBrightness = brightnessState.windowBrightness
                    ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    Box(modifier = modifier) {
        content()
        if (dialogWindow != null) {
            ReaderContentOverlay(
                brightness = brightnessState.overlayValue,
                color = null,
                colorBlendMode = null,
            )
        }
    }
}

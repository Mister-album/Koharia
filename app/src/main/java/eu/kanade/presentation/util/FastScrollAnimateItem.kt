package eu.kanade.presentation.util

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tachiyomi.presentation.core.motion.LocalEInkDisplayPolicy

// https://issuetracker.google.com/352584409
@Composable
context(itemScope: LazyItemScope)
fun Modifier.animateItemFastScroll() = with(itemScope) {
    if (LocalEInkDisplayPolicy.current.enabled) {
        this@animateItemFastScroll
    } else {
        this@animateItemFastScroll.animateItem(fadeInSpec = null, fadeOutSpec = null)
    }
}

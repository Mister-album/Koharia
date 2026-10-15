package koharia.connection

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

internal suspend fun verifySeriesActionsNavigation(
    navigator: () -> Navigator?,
    provider: String,
    onDestination: suspend () -> Unit = {},
    destination: (Screen) -> Boolean,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    check(context.packageName == "app.koharia.dev.devicefixture")
    suspend fun <T> await(stage: String, block: suspend () -> T): T {
        try {
            return withTimeout(15_000) { block() }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            fun labels(node: AccessibilityNodeInfo?): List<String> {
                if (node == null) return emptyList()
                return buildList {
                    if (node.isVisibleToUser) {
                        node.text?.toString()?.let(::add)
                        node.contentDescription?.toString()?.let(::add)
                    }
                    repeat(node.childCount) { addAll(labels(node.getChild(it))) }
                }
            }
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "series_actions_timeout",
                        "$stage;screen=${navigator()?.lastItem?.javaClass?.simpleName};" +
                            "labels=${labels(instrumentation.uiAutomation.rootInActiveWindow)}",
                    )
                },
            )
            throw failure
        }
    }
    val original = await("initial-details") {
        while (navigator()?.lastItem !is MangaScreen) delay(50)
        navigator()!!.lastItem
    }
    fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (
                node.text?.toString() == text || node.contentDescription?.toString() == text ||
                    (text == provider && node.text?.toString()?.startsWith("$provider · ") == true)
                )
        ) {
            return node
        }
        repeat(node.childCount) { find(node.getChild(it), text)?.let { return it } }
        return null
    }
    suspend fun tap(text: String) {
        val node = await("tap:$text") {
            var found: AccessibilityNodeInfo? = null
            while (found == null) {
                found = find(instrumentation.uiAutomation.rootInActiveWindow, text)
                if (found == null) delay(50)
            }
            found
        }
        var clickable = node
        while (!clickable.isClickable) clickable = checkNotNull(clickable.parent)
        check(clickable.isVisibleToUser && clickable.isEnabled)
        check(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "UI click rejected: $text" }
    }
    tap(context.stringResource(MR.strings.action_menu_overflow_description))
    tap(context.stringResource(MR.strings.connection_series_actions))
    await("destination") {
        while (navigator()?.lastItem?.let(destination) != true) delay(50)
        while (find(instrumentation.uiAutomation.rootInActiveWindow, provider) == null) delay(50)
    }
    onDestination()
    check(navigator()!!.items.size == 2 && navigator()!!.items.first() === original)
    tap(context.stringResource(MR.strings.action_bar_up_description))
    await("returned-details") {
        while (navigator()?.lastItem !== original) delay(50)
        while (find(
                instrumentation.uiAutomation.rootInActiveWindow,
                context.stringResource(MR.strings.action_menu_overflow_description),
            ) == null
        ) {
            delay(50)
        }
    }
    check(navigator()!!.items.size == 1)
    instrumentation.sendStatus(
        0,
        Bundle().apply {
            putString("series_actions_navigation", "$provider;standard-details;menu;provider-label;returned")
        },
    )
}

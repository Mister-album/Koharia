package eu.kanade.tachiyomi.ui.eink

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.presentation.core.components.EInkCircularProgressIndicator
import tachiyomi.presentation.core.motion.EInkAnimatedVisibility
import tachiyomi.presentation.core.motion.EInkDisplayPolicy
import tachiyomi.presentation.core.motion.ProvideEInkDisplayPolicy

@RunWith(AndroidJUnit4::class)
class EInkMotionDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<EInkMotionFixtureActivity>()

    @Test
    fun motionPolicyFramesAreDeterministic() {
        var visible by mutableStateOf(false)
        var scenario by mutableStateOf(Scenario.E_INK_VISIBILITY)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    when (scenario) {
                        Scenario.E_INK_VISIBILITY,
                        Scenario.NORMAL_VISIBILITY,
                        -> ProvideEInkDisplayPolicy(
                            EInkDisplayPolicy(enabled = scenario == Scenario.E_INK_VISIBILITY),
                        ) {
                            EInkAnimatedVisibility(
                                visible = visible,
                                enter = fadeIn(tween(1000)),
                                exit = fadeOut(tween(1000)),
                            ) {
                                Box(Modifier.size(96.dp).background(Color.Black).testTag(TARGET_TAG))
                            }
                        }

                        Scenario.E_INK_LOADING -> ProvideEInkDisplayPolicy(EInkDisplayPolicy(enabled = true)) {
                            EInkCircularProgressIndicator(modifier = Modifier.testTag(INDICATOR_TAG))
                        }
                    }
                }
            }
        }

        composeRule.runOnIdle { visible = true }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(TARGET_TAG).assertExists()
        val firstFrame = composeRule.onRoot().captureToImage().pixelHash()
        composeRule.mainClock.advanceTimeBy(1000)

        assertEquals(firstFrame, composeRule.onRoot().captureToImage().pixelHash())

        composeRule.runOnIdle {
            visible = false
            scenario = Scenario.NORMAL_VISIBILITY
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { visible = true }
        composeRule.mainClock.advanceTimeByFrame()
        val normalFirstFrame = composeRule.onRoot().captureToImage().pixelHash()
        composeRule.mainClock.advanceTimeBy(1000)

        assertNotEquals(normalFirstFrame, composeRule.onRoot().captureToImage().pixelHash())

        composeRule.runOnIdle { scenario = Scenario.E_INK_LOADING }
        composeRule.mainClock.advanceTimeByFrame()
        val loadingFirstFrame = composeRule.onRoot().captureToImage().pixelHash()
        composeRule.mainClock.advanceTimeBy(2000)

        assertEquals(loadingFirstFrame, composeRule.onRoot().captureToImage().pixelHash())
        composeRule.onNodeWithTag(INDICATOR_TAG).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
        )
    }

    private fun androidx.compose.ui.graphics.ImageBitmap.pixelHash(): Int {
        val bitmap = asAndroidBitmap()
        var result = 1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                result = 31 * result + bitmap.getPixel(x, y)
            }
        }
        return result
    }

    private companion object {
        const val TARGET_TAG = "eink-target"
        const val INDICATOR_TAG = "eink-indeterminate-indicator"
    }

    private enum class Scenario {
        E_INK_VISIBILITY,
        NORMAL_VISIBILITY,
        E_INK_LOADING,
    }
}

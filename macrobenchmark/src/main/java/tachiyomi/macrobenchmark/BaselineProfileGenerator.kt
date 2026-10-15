package tachiyomi.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import java.util.regex.Pattern

class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = ReaderProfileJourney().use { reader ->
        baselineProfileRule.collect(
            packageName = BenchmarkTarget.PACKAGE,
            maxIterations = 15,
            stableIterations = 3,
            strictStability = true,
            profileBlock = {
                pressHome()
                startActivityAndWait()

                fun click(label: String) {
                    val node = device.wait(Until.findObject(By.text(Pattern.compile(label))), 10_000)
                    checkNotNull(node) { "Missing current navigation entry: $label; complete fixture onboarding first" }
                    node.click()
                    device.waitForIdle()
                }
                click("History|历史")
                click("More|更多")
                click("Settings|设置")
                check(device.wait(Until.hasObject(By.text(Pattern.compile("Appearance|外观"))), 10_000)) {
                    "Current settings categories did not appear"
                }
                device.pressBack()
                reader.readAll()
            },
        )
    }
}

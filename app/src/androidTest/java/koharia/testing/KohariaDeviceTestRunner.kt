package koharia.testing

import android.app.Activity
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** A second boundary for IDE or direct ADB runs that do not execute the Gradle task guard. */
class KohariaDeviceTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        if (targetContext.packageName !in FIXTURE_PACKAGES) {
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply {
                    putString(
                        "shortMsg",
                        "Refusing device tests against a manual app. Build with deviceTestFixture=true.",
                    )
                },
            )
            return
        }
        super.onStart()
    }

    companion object {
        private val FIXTURE_PACKAGES = setOf("app.koharia.dev.devicefixture", "app.koharia.dev.einkfixture")
    }
}

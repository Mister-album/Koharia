package eu.kanade.tachiyomi.crash

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.view.WindowCompat
import eu.kanade.presentation.crash.CrashScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.view.setSafeComposeContent

class CrashActivity : BaseActivity() {

    protected override val usePersistedEInkPreferences = false

    override fun applyAppTheme(activity: Activity) {
        activity.setTheme(R.style.Theme_Tachiyomi)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val exception = GlobalExceptionHandler.getThrowableFromIntent(intent)
        setSafeComposeContent {
            CrashScreen(
                exception = exception,
                onRestartClick = {
                    finishAffinity()
                    startActivity(Intent(this@CrashActivity, MainActivity::class.java))
                },
            )
        }
    }
}

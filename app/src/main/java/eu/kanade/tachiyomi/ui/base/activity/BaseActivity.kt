package eu.kanade.tachiyomi.ui.base.activity

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import eu.kanade.domain.ui.EInkPreferences
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegate
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegateImpl
import eu.kanade.tachiyomi.ui.base.delegate.ThemingDelegate
import eu.kanade.tachiyomi.ui.base.delegate.ThemingDelegateImpl
import eu.kanade.tachiyomi.util.system.prepareTabletUiContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

open class BaseActivity :
    AppCompatActivity(),
    SecureActivityDelegate by SecureActivityDelegateImpl(),
    ThemingDelegate by ThemingDelegateImpl() {

    protected open val usePersistedEInkPreferences = true

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.prepareTabletUiContext())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAppTheme(this)
        super.onCreate(savedInstanceState)
        if (usePersistedEInkPreferences && Injekt.get<EInkPreferences>().enabled.get()) {
            disableActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN)
        }
    }

    override fun finish() {
        super.finish()
        if (usePersistedEInkPreferences && Injekt.get<EInkPreferences>().enabled.get()) {
            disableActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE)
        }
    }

    @Suppress("DEPRECATION")
    private fun disableActivityTransition(transitionType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(transitionType, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }
}

package eu.kanade.tachiyomi.ui.setting.track

import android.net.Uri

class TrackLoginActivity : BaseOAuthLoginActivity() {

    override fun handleResult(uri: Uri) {
        returnToSettings()
    }
}

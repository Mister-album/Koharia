package eu.kanade.tachiyomi.data.updater

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.util.system.launchRequestPackageInstallsPermission
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.MR

class AppUpdateInstallActivity : Activity() {

    private var requestedInstallPermission = false
    private var launchedInstaller = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        continueInstallFlow()
    }

    override fun onResume() {
        super.onResume()
        if (!launchedInstaller) {
            continueInstallFlow()
        }
    }

    private fun continueInstallFlow() {
        val apkUri = intent.data ?: run {
            finish()
            return
        }

        if (!packageManager.canRequestPackageInstalls()) {
            if (requestedInstallPermission) {
                finish()
                return
            }
            requestedInstallPermission = true
            toast(MR.strings.app_update_install_permission_required)
            launchRequestPackageInstallsPermission()
            return
        }

        launchInstaller(apkUri)
    }

    @Suppress("DEPRECATION")
    private fun launchInstaller(apkUri: Uri) {
        if (launchedInstaller) return
        launchedInstaller = true

        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(apkUri, ExtensionInstaller.APK_MIME)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(installIntent)
        } catch (e: Exception) {
            toast(e.message)
        } finally {
            finish()
        }
    }

    companion object {
        fun intent(context: Context, uri: Uri): Intent {
            return Intent(context, AppUpdateInstallActivity::class.java).apply {
                data = uri
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }
}

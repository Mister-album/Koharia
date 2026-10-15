package koharia.importing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File
import java.net.URI

/** Exported entry points must not lend the app's private-file access to an external sender. */
internal fun isExternalFileUri(context: Context, uri: Uri): Boolean = runCatching {
    when (uri.scheme) {
        "file" -> {
            val sharedRoots = listOf(Environment.getExternalStorageDirectory(), File("/storage"), File("/mnt/media_rw"))
            val privateRoots = listOf(context.dataDir, context.createDeviceProtectedStorageContext().dataDir) +
                context.getExternalFilesDirs(null).filterNotNull() + context.externalCacheDirs.filterNotNull()
            isExternalMediaFile(File(URI(uri.toString())), sharedRoots, privateRoots)
        }
        "content" -> {
            val authority = uri.authority?.substringAfterLast('@') ?: return@runCatching false
            val provider = context.packageManager.resolveContentProvider(authority, 0)
            provider != null && provider.applicationInfo.uid != context.applicationInfo.uid
        }
        else -> false
    }
}.getOrDefault(false)

internal fun canReadIncomingUri(activity: Activity, intent: Intent, uri: Uri, newIntent: Boolean = false): Boolean {
    if (uri.scheme != "content") return true
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Capture permissions before forwarding or asynchronous navigation loses the original caller.
            val caller = if (newIntent) activity.currentCaller else activity.initialCaller
            caller.checkContentUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            val authority = uri.authority?.substringAfterLast('@') ?: return@runCatching false
            val provider = activity.packageManager.resolveContentProvider(authority, 0) ?: return@runCatching false
            hasLegacyIncomingReadAccess(intent, uri, provider)
        }
    }.getOrDefault(false)
}

internal fun hasLegacyIncomingReadAccess(intent: Intent, uri: Uri, provider: ProviderInfo): Boolean {
    val publiclyReadable = provider.exported && provider.readPermission == null &&
        provider.pathPermissions.orEmpty().none { it.readPermission != null && it.match(uri.path.orEmpty()) }
    if (publiclyReadable) return true
    if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return false
    // Android validates grants for data/ClipData. A stray EXTRA_STREAM must not borrow another URI's grant.
    return intent.data == uri || intent.clipData?.let { clip ->
        (0 until clip.itemCount).any { clip.getItemAt(it).uri == uri }
    } == true
}

internal fun validatedExternalBackupIntent(activity: Activity, intent: Intent, newIntent: Boolean = false): Intent {
    val uri = intent.data ?: return intent
    if (intent.action != Intent.ACTION_VIEW || !uri.toString().endsWith(".kohariabackup", ignoreCase = true)) {
        return intent
    }
    return if (isExternalFileUri(activity, uri) && canReadIncomingUri(activity, intent, uri, newIntent)) {
        intent
    } else {
        Intent(Intent.ACTION_MAIN)
    }
}

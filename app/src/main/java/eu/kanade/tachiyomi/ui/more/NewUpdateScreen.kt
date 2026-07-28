package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.NewUpdateScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadJob
import eu.kanade.tachiyomi.util.system.openInBrowser
import tachiyomi.core.common.DocumentationUrls

class NewUpdateScreen(
    private val versionName: String,
    private val changelogInfo: String,
    private val downloadLinks: List<String>,
    private val expectedSize: Long?,
    private val expectedSha256: String?,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val changelogInfoNoChecksum = remember {
            changelogInfo.replace("""---(\R|.)*Checksums(\R|.)*""".toRegex(), "")
        }

        NewUpdateScreen(
            versionName = versionName,
            changelogInfo = changelogInfoNoChecksum,
            onOpenInBrowser = { context.openInBrowser(DocumentationUrls.changelog(context, versionName)) },
            onRejectUpdate = navigator::pop,
            onAcceptUpdate = {
                AppUpdateDownloadJob.start(
                    context = context,
                    urls = downloadLinks,
                    title = versionName,
                    expectedSize = expectedSize,
                    expectedSha256 = expectedSha256,
                )
                navigator.pop()
            },
        )
    }
}

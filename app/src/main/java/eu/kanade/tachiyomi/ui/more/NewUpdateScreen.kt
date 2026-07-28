package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.NewUpdateScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadJob
import eu.kanade.tachiyomi.data.updater.AppUpdateReleaseNotes
import eu.kanade.tachiyomi.util.system.openInBrowser
import tachiyomi.core.common.DocumentationUrls

class NewUpdateScreen(
    private val versionName: String,
    private val changelogInfo: String,
    private val downloadLinks: List<String>,
    private val expectedSize: Long?,
    private val expectedSha256: String?,
    private val updateAvailable: Boolean,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val language = context.resources.configuration.locales[0].language
        val localizedChangelog = remember(changelogInfo, language) {
            AppUpdateReleaseNotes.select(changelogInfo, language)
        }

        NewUpdateScreen(
            versionName = versionName,
            changelogInfo = localizedChangelog,
            updateAvailable = updateAvailable,
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

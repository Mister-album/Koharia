package koharia.importing

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.NavigatorDisposeBehavior
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.view.setComposeContent
import koharia.core.migration.Migrator
import tachiyomi.core.common.Constants
import tachiyomi.domain.manga.model.Manga

internal interface ExternalMediaFlowHost {
    fun openReader(intent: Intent)
    fun finishImport(manga: Manga?, openAfterImport: Boolean)
    fun closeFlow()
}

abstract class ExternalMediaHostActivity(
    private val mode: String,
) : BaseActivity(), ExternalMediaFlowHost {

    private var readerWasLaunched = false

    init {
        registerSecureActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readerWasLaunched = savedInstanceState?.getBoolean(STATE_READER_WAS_LAUNCHED) ?: false
        Migrator.awaitAndRelease()
        enableEdgeToEdge()

        val temporaryImportUri = IncomingMediaNavigation.validatedImportUri(this, intent)
        val uriValues = temporaryImportUri?.let(::listOf)
            ?: IncomingMediaNavigation.mediaUriValues(intent)
        if (uriValues.isEmpty()) {
            finish()
            return
        }

        setComposeContent {
            Navigator(
                screen = ExternalMediaImportScreen(
                    uriValues = uriValues,
                    startAtImportConfiguration = mode == IncomingMediaNavigation.EXTERNAL_MEDIA_MODE_IMPORT,
                    openImmediately = mode == IncomingMediaNavigation.EXTERNAL_MEDIA_MODE_OPEN,
                    skipActionSelection = true,
                ),
                disposeBehavior = NavigatorDisposeBehavior(
                    disposeNestedNavigators = false,
                    disposeSteps = true,
                ),
            ) { navigator ->
                DefaultNavigatorScreenTransition(navigator = navigator)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_READER_WAS_LAUNCHED, readerWasLaunched)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (readerWasLaunched) {
            readerWasLaunched = false
            openMainActivity(manga = null, openAfterImport = false)
        }
    }

    override fun openReader(intent: Intent) {
        readerWasLaunched = true
        startActivity(intent)
    }

    override fun finishImport(manga: Manga?, openAfterImport: Boolean) {
        openMainActivity(manga, openAfterImport)
    }

    override fun closeFlow() {
        finish()
    }

    private fun openMainActivity(manga: Manga?, openAfterImport: Boolean) {
        val target = Intent(this, MainActivity::class.java).apply {
            if (openAfterImport && manga != null) {
                action = Constants.SHORTCUT_MANGA
                putExtra(Constants.MANGA_EXTRA, manga.id)
                putExtra(Constants.FROM_SOURCE_EXTRA, true)
                putExtra(Constants.MANGA_SOURCE_EXTRA, manga.source)
                putExtra(Constants.MANGA_URL_EXTRA, manga.url)
            } else {
                action = Constants.SHORTCUT_LIBRARY
            }
            if (intent.getBooleanExtra(IncomingMediaNavigation.EXTRA_REPLACE_EXTERNAL_OPEN_TASK, false)) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            } else {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
        startActivity(target)
        finish()
    }

    private companion object {
        const val STATE_READER_WAS_LAUNCHED = "reader_was_launched"
    }
}

class ExternalMediaImportActivity : ExternalMediaHostActivity(
    IncomingMediaNavigation.EXTERNAL_MEDIA_MODE_IMPORT,
)

class ExternalMediaOpenActivity : ExternalMediaHostActivity(
    IncomingMediaNavigation.EXTERNAL_MEDIA_MODE_OPEN,
)

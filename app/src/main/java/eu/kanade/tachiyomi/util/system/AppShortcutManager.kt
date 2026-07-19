package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import logcat.LogPriority
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

object AppShortcutManager {

    fun updateLibraryShortcuts(context: Context, classificationEnabled: Boolean) {
        val shortcuts = if (classificationEnabled) {
            listOf(
                createShortcut(
                    context = context,
                    id = SHORTCUT_COMICS_ID,
                    label = context.stringResource(MR.strings.label_comics),
                    iconRes = R.drawable.sc_collections_bookmark_48dp,
                    action = Constants.SHORTCUT_LIBRARY,
                    rank = 0,
                ),
                createShortcut(
                    context = context,
                    id = SHORTCUT_BOOKS_ID,
                    label = context.stringResource(MR.strings.label_books),
                    iconRes = R.drawable.sc_book_48dp,
                    action = Constants.SHORTCUT_UPDATES,
                    rank = 1,
                ),
            )
        } else {
            listOf(
                createShortcut(
                    context = context,
                    id = SHORTCUT_LIBRARY_ID,
                    label = context.stringResource(MR.strings.label_library),
                    iconRes = R.drawable.sc_collections_bookmark_48dp,
                    action = Constants.SHORTCUT_LIBRARY,
                    rank = 0,
                ),
            )
        }

        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) { "Failed to update app shortcuts" }
        }
    }

    private fun createShortcut(
        context: Context,
        id: String,
        label: String,
        iconRes: Int,
        action: String,
        rank: Int,
    ): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action
        }
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, iconRes))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    private const val SHORTCUT_LIBRARY_ID = "dynamic_library"
    private const val SHORTCUT_COMICS_ID = "dynamic_comics"
    private const val SHORTCUT_BOOKS_ID = "dynamic_books"
}

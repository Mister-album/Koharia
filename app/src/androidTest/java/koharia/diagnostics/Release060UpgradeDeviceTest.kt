package koharia.diagnostics

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.ui.main.MainActivity
import koharia.connection.ConnectionPreferences
import koharia.connection.SharedConfigMigration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Two explicit phases after upgrading a seeded v0.6.0 E-Ink fixture; never seeds or resets an installed app. */
@RunWith(AndroidJUnit4::class)
class Release060UpgradeDeviceTest {
    @Test
    fun verifyFirstUpgradeKeepsSharedConfiguration(): Unit = runBlocking(Dispatchers.IO) {
        requireUpgradeFixture()
        val store = Injekt.get<PreferenceStore>()
        verifyChosenConfiguration()
        verifyPersistedData()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertFalse(it.isFinishing) }
            withTimeout(15_000) {
                while (store.getInt(Preference.appStateKey("last_version_code")).get() != BuildConfig.VERSION_CODE) {
                    delay(50)
                }
            }
        }
        verifyChosenConfiguration()
        verifyPersistedData()
        println(
            "UPGRADE_VERIFIED: old=12/21 current=${BuildConfig.VERSION_CODE}/${Database.Schema.version} owners=4 pending=3 shared=retained",
        )
    }

    @Test
    fun verifyReopenedAfterProcessRestart(): Unit = runBlocking(Dispatchers.IO) {
        requireUpgradeFixture()
        verifyChosenConfiguration()
        verifyPersistedData()
        assertEquals(
            BuildConfig.VERSION_CODE,
            Injekt.get<PreferenceStore>().getInt(Preference.appStateKey("last_version_code")).get(),
        )
        println("UPGRADE_REOPENED: configuration, identities, reading data and pending operations retained")
    }

    private fun requireUpgradeFixture() {
        assumeTrue(
            "Requires the explicit historical APK upgrade journey",
            InstrumentationRegistry.getArguments().getString("upgrade060") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.einkfixture", context.packageName)
        assertTrue(BuildConfig.VERSION_CODE > 12)
        assertTrue(
            "Requires old APK seed; this test never initializes data",
            Injekt.get<PreferenceStore>().getBoolean("upgrade_fixture_060_seeded").get(),
        )
        assertEquals("retained-060-to-current", File(context.filesDir, "upgrade-060-retained.txt").readText())
    }

    private fun verifyChosenConfiguration() {
        val store = Injekt.get<PreferenceStore>()
        assertFalse(Injekt.get<SharedConfigMigration>().isPending())
        assertEquals(610, store.getInt("connection_shared::pref_double_tap_anim_speed").get())
        assertFalse(store.getBoolean("connection_shared::pref_show_page_number_key", true).get())
        assertEquals(95060001L, Injekt.get<ConnectionPreferences>().activeConnectionId.get())
    }

    private suspend fun verifyPersistedData() {
        val driver = Injekt.get<SqlDriver>()
        assertEquals(listOf(Database.Schema.version.toString()), driver.strings("PRAGMA user_version"))
        assertEquals(listOf("ok"), driver.strings("PRAGMA integrity_check"))
        assertTrue(driver.strings("PRAGMA foreign_key_check").isEmpty())
        val connections = Injekt.get<ConnectionPreferences>()
        val providers = listOf("komga", "local-folder", "lanraragi", "smanga")
        assertEquals(4, connections.getProfiles().size)
        providers.forEachIndexed { index, provider ->
            val profile = connections.getProfiles().single { it.id == 95060001L + index }
            assertEquals(provider, profile.providerId)
            assertEquals("Upgrade 060 $provider", profile.name)
            assertEquals(provider, sourcePreferences("source_${profile.id}").getString("upgrade_owner_marker", null))
        }
        assertEquals(
            (0..3).map { "${950 + it}:${95060001 + it}:/upgrade-060/$it:Retain note" },
            driver.strings("SELECT _id || ':' || source || ':' || url || ':' || notes FROM mangas ORDER BY _id"),
        )
        assertEquals(
            (0..3).map { "${960 + it}:${950 + it}:1:1:8" },
            driver.strings(
                "SELECT _id || ':' || manga_id || ':' || read || ':' || bookmark || ':' || last_page_read FROM chapters ORDER BY _id",
            ),
        )
        assertEquals(listOf("4:1600"), driver.strings("SELECT count(*) || ':' || sum(time_read) FROM history"))
        assertEquals(listOf("4"), driver.strings("SELECT count(*) FROM mangas_categories WHERE category_id=95"))
        assertEquals(listOf("Upgrade 060 category"), driver.strings("SELECT name FROM categories WHERE _id=95"))
        assertEquals(
            listOf("960:950:0.4:8", "961:951:0.4:8"),
            driver.strings(
                "SELECT chapter_id || ':' || manga_id || ':' || progression || ':' || position_index FROM epub_progress ORDER BY chapter_id",
            ),
        )
        assertEquals(
            listOf("Retain bookmark", "Retain bookmark"),
            driver.strings("SELECT note FROM epub_bookmark ORDER BY chapter_id"),
        )
        assertEquals(listOf("5", "5"), driver.strings("SELECT sentence_index FROM tts_progress ORDER BY chapter_id"))
        assertEquals(
            listOf("95060003:frozen-archive:3:1:7"),
            driver.strings(
                "SELECT connection_id || ':' || archive_id || ':' || page_index || ':' || pending || ':' || revision FROM lanraragi_read_state",
            ),
        )
        assertEquals(
            listOf("95060004:fixture-account:4:3:1:7"),
            driver.strings(
                "SELECT connection_id || ':' || account_key || ':' || chapter_id || ':' || page_index || ':' || pending || ':' || revision FROM smanga_read_state",
            ),
        )
        assertEquals(
            listOf("95060004:fixture-account:fixture-event:0"),
            driver.strings(
                "SELECT connection_id || ':' || account_key || ':' || event_id || ':' || status FROM smanga_history_event",
            ),
        )
        assertTrue(
            driver.strings(
                "SELECT name FROM sqlite_master WHERE type='table'",
            ).containsAll(listOf("kavita_operation", "suwayomi_operation", "library_storage_record")),
        )
    }

    private suspend fun SqlDriver.strings(sql: String): List<String> = executeQuery(null, sql, { cursor ->
        QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(0).orEmpty()) })
    }, 0).await()
}

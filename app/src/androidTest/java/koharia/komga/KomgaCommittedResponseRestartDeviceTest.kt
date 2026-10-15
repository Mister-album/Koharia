package koharia.komga

import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import eu.kanade.tachiyomi.data.track.komga.KomgaApi
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
import koharia.komga.progress.KomgaApiPageTransport
import koharia.komga.progress.KomgaProgressWorkDispatcher
import koharia.komga.progress.KomgaProgressWorkManager
import koharia.source.komga.KomgaSource
import koharia.source.komga.captureKomgaProgressSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class KomgaCommittedResponseRestartDeviceTest {
    @Serializable
    private data class Checkpoint(
        val connection: Long,
        val process: Int,
        val active: Long,
        val hadActive: Boolean,
        val owner: String = "",
        val baseline: String = "",
    )

    @Test
    fun committedPageReadAndUnreadRecoverAfterProcessDiesBeforeResponse(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val stage = args.getString("committedStage")
        assumeTrue(stage in setOf("prepare", "recover", "cleanup"))
        val mode = checkNotNull(args.getString("committedMode"))
        require(mode in setOf("page", "read", "unread"))
        val run = checkNotNull(args.getString("committedRun"))
        require(run.matches(Regex("[0-9]+")))
        val address = checkNotNull(args.getString("committedAddress"))
        require(address.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+")))
        val file = File(context.cacheDir, "komga-committed-$run-$mode.json")
        val profiles = Injekt.get<ConnectionProfileManager>()
        val preferences = Injekt.get<ConnectionPreferences>()
        val sources = Injekt.get<SourceManager>()
        val pages = Injekt.get<KomgaPageEventRepository>()
        val intents = Injekt.get<KomgaReadStatusRepository>()
        val foreground = ActivityScenario.launch(EInkMotionFixtureActivity::class.java)
        if (stage == "prepare") {
            check(!file.exists())
            val active = preferences.activeConnectionId.get()
            val hadActive = preferences.activeConnectionId.isSet()
            val profile = profiles.add("komga", "Committed $run $mode")
            var checkpoint = Checkpoint(profile.id, Process.myPid(), active, hadActive)
            file.writeText(Json.encodeToString(checkpoint))
            profiles.commitConfiguration(profile.id) { draft ->
                check(
                    draft.edit().putString("Address", address).putString("AuthMode", "Credentials")
                        .putString("Username", "fixture").putString("Password", "fixture-password").commit(),
                )
            }
            val source = sources.get(profile.id) as KomgaSource
            val session = captureKomgaProgressSession(source, sources)
            val key = KomgaReadStatusKey(profile.id, session.account.key, "book")
            val transport =
                KomgaApiPageTransport(
                    KomgaApi(6, source.client),
                    session,
                    "$address/api/v1/books/book",
                    session.guard::requireCurrent,
                )
            val baseline = transport.read().encode()
            checkpoint = checkpoint.copy(owner = key.ownerKey, baseline = baseline)
            file.writeText(Json.encodeToString(checkpoint))
            if (mode == "page") {
                pages.record(key, 0, 5, 10, 100, baseline, session.guard::requireCurrent)
            } else {
                intents.record(key, mode == "read", 100, baseline, emptyList(), session.guard::requireCurrent)
            }
            // The owned host commits the real request, blocks its reply, then force-stops only this fixture.
            Injekt.get<KomgaProgressWorkDispatcher>().dispatch(key)
            error("Expected the host to interrupt this process after remote commit, before any response")
        }
        val checkpoint = Json.decodeFromString<Checkpoint>(file.readText())
        val key = checkpoint.owner.takeIf(String::isNotBlank)?.let {
            KomgaReadStatusKey(checkpoint.connection, it, "book")
        }
        if (stage == "recover") {
            checkNotNull(key)
            assertNotEquals(checkpoint.process, Process.myPid())
            assertEquals(checkpoint.owner, (sources.get(checkpoint.connection) as KomgaSource).progressAccount().key)
            // Production App startup discovers the durable in-flight request and schedules its existing worker.
            withTimeout(65_000) {
                while ((if (mode == "page") pages.get(key)?.phase else intents.get(key)?.phase) !=
                    KomgaReadStatusPhase.SYNCED
                ) {
                    delay(100)
                }
            }
            assertNull(pages.attempt(key))
            assertNull(intents.attempt(key))
            if (mode == "page") {
                val saved = checkNotNull(pages.get(key))
                assertEquals(5L, saved.page)
                assertEquals(1L, saved.generation)
                assertEquals(100L, saved.readAt)
            } else {
                val saved = checkNotNull(intents.get(key))
                assertEquals(mode == "read", saved.read)
                assertEquals(1L, saved.revision)
                assertEquals(100L, saved.requestedAt)
            }
        }
        if (key !=
            null
        ) {
            WorkManager.getInstance(context).cancelUniqueWork(KomgaProgressWorkManager.name(key)).result.get()
        }
        check(
            profiles.profiles().singleOrNull { it.id == checkpoint.connection }?.name?.let {
                it.startsWith("Committed $run $mode")
            } !=
                false,
        )
        profiles.remove(checkpoint.connection).getOrThrow()
        pages.removeConnection(checkpoint.connection)
        intents.removeConnection(checkpoint.connection)
        if (checkpoint.hadActive) {
            preferences.activeConnectionId.set(
                checkpoint.active,
            )
        } else {
            preferences.activeConnectionId.delete()
        }
        assertTrue(file.delete())
        foreground.close()
    }
}

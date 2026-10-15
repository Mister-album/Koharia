package koharia.komga

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.track.komga.KomgaApi
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionPreferences
import koharia.domain.komga.KomgaPageAttempt
import koharia.domain.komga.KomgaPageEvent
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaReadStatusAttempt
import koharia.domain.komga.KomgaReadStatusIntent
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
import koharia.komga.progress.KomgaApiPageTransport
import koharia.komga.progress.KomgaProgressBaseline
import koharia.komga.progress.KomgaProgressCoordinator
import koharia.komga.progress.KomgaProgressDispatchResult
import koharia.komga.progress.KomgaProgressOrdering
import koharia.source.komga.KomgaSource
import koharia.source.komga.captureKomgaProgressSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import tachiyomi.data.Database
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KomgaReadStatusSqlScaleDeviceTest {
    @Test
    fun explicitReadUnreadUsesRealSqlAcrossThreeScales(): Unit = runBlocking(Dispatchers.IO) {
        for (count in listOf(1, 100, 1000)) exercise(count, false)
    }

    @Test
    fun comparisonFailureRetainsIntentAndOnlyRetriesPendingBooks(): Unit = runBlocking(Dispatchers.IO) {
        exercise(3, true)
    }

    private suspend fun exercise(count: Int, failure: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val id = System.nanoTime()
        val connections = Injekt.get<ConnectionPreferences>()
        check(!connections.isKnownConnectionId(id) && !connections.isKnownConnectionId(id + 1))
        val preferences = sourcePreferences("source_$id")
        check(preferences.all.isEmpty())
        val actual = Injekt.get<KomgaReadStatusRepository>()
        val actualPages = Injekt.get<KomgaPageEventRepository>()
        val role = if (failure) "failure" else "scale-$count"
        val address = "http://127.0.0.1:18941/$role"
        var queries = 0
        var rows = 0
        var gets = 0
        var attempts = 0
        var pageGets = 0
        var pageAttempts = 0
        var begins = 0
        var settles = 0
        try {
            check(
                preferences.edit().putString("Address", address).putString("AuthMode", "Credentials")
                    .putString("Username", "fixture").putString("Password", "fixture").commit(),
            )
            val source = KomgaSource(id)
            val sources = object : SourceManager by Injekt.get<SourceManager>() {
                override fun get(sourceKey: Long) = source.takeIf { sourceKey == id }
            }
            val session = captureKomgaProgressSession(source, sources)
            val owner = session.account.key
            for ((connection, account) in listOf(id to owner, id to "foreign", id + 1 to owner)) {
                check(actual.forOwner(connection, account).isEmpty())
                check(actual.attempts(connection, account).isEmpty())
                check(actualPages.pending(connection, account).isEmpty())
            }
            fun key(book: String) = KomgaReadStatusKey(id, owner, book)
            val measured = object : KomgaReadStatusRepository by actual {
                override suspend fun pending(connectionId: Long, ownerKey: String): List<KomgaReadStatusIntent> {
                    check(connectionId == id && ownerKey == owner)
                    queries++
                    return actual.pending(connectionId, ownerKey).also { rows += it.size }
                }
                override suspend fun get(key: KomgaReadStatusKey): KomgaReadStatusIntent? {
                    check(key.connectionId == id && key.ownerKey == owner)
                    gets++
                    return actual.get(key)
                }
                override suspend fun attempt(key: KomgaReadStatusKey): KomgaReadStatusAttempt? {
                    check(key.connectionId == id && key.ownerKey == owner)
                    attempts++
                    return actual.attempt(key)
                }
                override suspend fun begin(
                    intent: KomgaReadStatusIntent,
                    baseline: String,
                    checkOwner: () -> Unit,
                ) = actual.begin(intent, baseline, checkOwner).also {
                    check(it != null)
                    begins++
                }
                override suspend fun settle(
                    attempt: KomgaReadStatusAttempt,
                    phase: KomgaReadStatusPhase,
                    confirmedBaseline: String?,
                    checkOwner: () -> Unit,
                ) = actual.settle(attempt, phase, confirmedBaseline, checkOwner).also {
                    check(it)
                    settles++
                }
                override suspend fun forOwner(connectionId: Long, ownerKey: String): List<KomgaReadStatusIntent> =
                    error("Dispatch must not load confirmed owner history")
            }
            val pages = object : KomgaPageEventRepository by actualPages {
                override suspend fun get(key: KomgaReadStatusKey): KomgaPageEvent? {
                    check(key.connectionId == id && key.ownerKey == owner)
                    pageGets++
                    return actualPages.get(key)
                }
                override suspend fun attempt(key: KomgaReadStatusKey): KomgaPageAttempt? {
                    check(key.connectionId == id && key.ownerKey == owner)
                    pageAttempts++
                    return actualPages.attempt(key)
                }
            }
            val ordering = KomgaProgressOrdering(measured, pages)
            val coordinator = KomgaProgressCoordinator(measured, pages, ordering)
            val api = KomgaApi(6, source.client)
            fun transport(book: String) = KomgaApiPageTransport(api, session, "$address/api/v1/books/$book") {
                session.guard.requireCurrent()
            }
            val baselines = (1..count).associate { "book-$it" to transport("book-$it").read() }
            Injekt.get<Database>().transaction {
                repeat(1000) {
                    val historyKey = key("history-$it")
                    val intent = actual.record(
                        historyKey,
                        false,
                        1,
                        baselines.getValue("book-1").copy(bookId = historyKey.bookId).encode(),
                        emptyList(),
                        session.guard::requireCurrent,
                    )
                    check(actual.finish(historyKey, intent.revision, KomgaReadStatusPhase.SYNCED))
                }
            }
            val history = actual.forOwner(id, owner)
            val foreignKeys =
                listOf(key("book-1").copy(ownerKey = "foreign"), key("book-1").copy(connectionId = id + 1))
            val foreign = foreignKeys.map {
                actual.record(it, true, 2, baselines.getValue("book-1").encode(), emptyList()) {}
            }
            val queued = (1..count).map { index ->
                actual.record(
                    key("book-$index"),
                    index % 2 == 0,
                    1000,
                    baselines.getValue("book-$index").encode(),
                    emptyList(),
                    session.guard::requireCurrent,
                )
            }
            var failures = 0
            suspend fun flush() {
                for (intent in measured.pending(id, owner)) {
                    try {
                        check(
                            coordinator.dispatch(
                                intent.key,
                                transport(intent.key.bookId),
                                session.guard::requireCurrent,
                            ) ==
                                KomgaProgressDispatchResult.SYNCED,
                        )
                    } catch (error: HttpException) {
                        check(failure && error.code == 503 && intent.key.bookId == "book-2")
                        failures++
                    }
                }
            }
            val started = System.nanoTime()
            flush()
            if (failure) {
                check(failures == 1 && actual.pending(id, owner).single().key == key("book-2"))
                check(actual.attempts(id, owner).isEmpty())
                flush()
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            check(queries == if (failure) 2 else 1)
            check(rows == count + if (failure) 1 else 0)
            check(gets == 5 * count + if (failure) 2 else 0)
            check(attempts == 3 * count + if (failure) 2 else 0)
            check(pageGets == 2 * count + if (failure) 1 else 0)
            check(pageAttempts == 3 * count + if (failure) 2 else 0)
            check(begins == count && settles == count)
            check(actual.pending(id, owner).isEmpty() && actual.attempts(id, owner).isEmpty())
            for (before in queued) {
                val baseline = baselines.getValue(before.key.bookId)
                val confirmed = if (before.read) {
                    baseline.copy(completed = true, progressModified = "confirmed")
                } else {
                    baseline.copy(progressPresent = false, page = null, completed = false, progressModified = null)
                }
                check(
                    actual.get(before.key) ==
                        before.copy(phase = KomgaReadStatusPhase.SYNCED, baseline = confirmed.encode()),
                )
                check(actualPages.get(before.key) == null && actualPages.attempt(before.key) == null)
            }
            check(actual.forOwner(id, owner).filter { it.key.bookId.startsWith("history-") } == history)
            check(foreignKeys.map { actual.get(it) } == foreign)
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "komga_intent_scale",
                        "pending=$count;failure=$failure;queries=$queries;rows=$rows;intent_get=$gets;" +
                            "intent_attempt=$attempts;page_get=$pageGets;page_attempt=$pageAttempts;" +
                            "begin=$begins;settle=$settles;elapsed_ms=$elapsed",
                    )
                },
            )
        } finally {
            actualPages.removeConnection(id)
            actualPages.removeConnection(id + 1)
            actual.removeConnection(id)
            actual.removeConnection(id + 1)
            context.deleteSharedPreferences("source_$id")
            check(sourcePreferences("source_$id").all.isEmpty())
            instrumentation.sendStatus(0, Bundle().apply { putString("komga_intent_cleanup", role) })
        }
    }
}

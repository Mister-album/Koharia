package koharia.komga

import android.os.Bundle
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import koharia.connection.ConnectionPreferences
import koharia.domain.komga.KomgaPageEvent
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaPageState
import koharia.domain.komga.KomgaReadStatusIntent
import koharia.domain.komga.KomgaReadStatusKey
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
import koharia.komga.progress.KomgaPageJournal
import koharia.komga.progress.KomgaProgressBaseline
import koharia.komga.progress.KomgaProgressOrdering
import koharia.komga.progress.KomgaProgressWakeups
import koharia.komga.progress.KomgaProgressWorkBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KomgaPageAllocationDeviceTest {
    @Test
    fun pageJournalRecordingHasBoundedMaterializationAcrossHistorySizes(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "app.koharia.dev.devicefixture")
        val id = System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val actual = Injekt.get<KomgaReadStatusRepository>()
        val actualPages = Injekt.get<KomgaPageEventRepository>()
        val database = Injekt.get<Database>()
        val key = KomgaReadStatusKey(id, "a", "book")
        val foreignKey = key.copy(ownerKey = "b")
        check(actual.forOwner(id, "a").isEmpty() && actual.forOwner(id, "b").isEmpty())
        check(actualPages.get(key) == null && actualPages.get(foreignKey) == null)
        val baseline = KomgaProgressBaseline(
            1, "book", "owned-publication", null, 100, 1000, false, false, false, null, false, null, null,
        )
        val encoded = baseline.encode()
        var intentGets = 0
        var intentRows = 0
        var pageRecords = 0
        var returnedEvents = 0
        var chapterCommits = 0
        var wakeChecks = 0
        var scheduled = 0
        val counted = object : KomgaReadStatusRepository by actual {
            override suspend fun get(key: KomgaReadStatusKey): KomgaReadStatusIntent? {
                check(key.connectionId == id && key.ownerKey == "a" && key.bookId == "book")
                intentGets++
                return actual.get(key).also { if (it != null) intentRows++ }
            }
            override suspend fun forOwner(connectionId: Long, ownerKey: String): List<KomgaReadStatusIntent> =
                error("Page must not load owner history")
            override suspend fun pending(connectionId: Long, ownerKey: String): List<KomgaReadStatusIntent> =
                error("Page must not consume pending operations")
        }
        val pages = object : KomgaPageEventRepository by actualPages {
            override suspend fun record(
                key: KomgaReadStatusKey,
                revision: Long,
                page: Long,
                total: Long,
                readAt: Long,
                baseline: String?,
                checkOwner: () -> Unit,
            ): KomgaPageEvent? {
                check(key.connectionId == id && key.ownerKey == "a" && key.bookId == "book")
                pageRecords++
                return actualPages.record(key, revision, page, total, readAt, baseline, checkOwner).also {
                    if (it != null) returnedEvents++
                }
            }
            override suspend fun pending(connectionId: Long, ownerKey: String): List<KomgaPageEvent> =
                error("Page must not enumerate pending events")
        }
        val ordering = KomgaProgressOrdering(counted, pages)
        val wakeups = KomgaProgressWakeups(object : KomgaProgressWorkBackend {
            override suspend fun states(key: KomgaReadStatusKey): List<WorkInfo.State> {
                check(key.connectionId == id && key.ownerKey == "a" && key.bookId == "book")
                wakeChecks++
                return if (scheduled == 0) emptyList() else listOf(WorkInfo.State.ENQUEUED)
            }
            override suspend fun append(key: KomgaReadStatusKey) {
                check(key.connectionId == id && key.ownerKey == "a" && key.bookId == "book")
                scheduled++
            }
        })
        val journal = KomgaPageJournal(database, pages, ordering, wakeups)
        try {
            val initial = actual.record(key, false, 1, encoded, emptyList()) {}
            check(actual.finish(key, initial.revision, KomgaReadStatusPhase.SYNCED))
            val originalIntent = checkNotNull(actual.get(key))
            val foreign = checkNotNull(actualPages.record(foreignKey, 0, 77, 1000, 77, encoded) {})
            val token = ordering.capture(key)
            var seeded = 0
            var sequence = 0L
            suspend fun record() {
                sequence++
                journal.record(token, encoded, (sequence % 999).toInt(), 1000, sequence, false, {}) {
                    chapterCommits++
                }
            }
            for (history in listOf(1000, 10000, 50000)) {
                database.transaction {
                    for (index in seeded until history) {
                        val historyKey = key.copy(bookId = "history-$index")
                        val evidence = baseline.copy(bookId = historyKey.bookId).encode()
                        val intent = actual.record(historyKey, false, 1, evidence, emptyList()) {}
                        check(actual.finish(historyKey, intent.revision, KomgaReadStatusPhase.SYNCED))
                        checkNotNull(
                            actualPages.reconcileLocal(
                                historyKey,
                                intent.revision,
                                KomgaPageState(null, null),
                                1,
                                1000,
                                1,
                                evidence,
                                false,
                            ) {},
                        )
                    }
                }
                seeded = history
                val historyKeys = (0 until history).map { "history-$it" }
                val beforeIntents = actual.forOwner(id, "a")
                val beforePages = actualPages.states(id, "a", historyKeys)
                check(beforePages.size == history && beforePages.values.all { it.settled && it.event != null })
                repeat(20) { record() }
                repeat(5) { sample ->
                    val before = listOf(intentGets, intentRows, pageRecords, returnedEvents, chapterCommits, wakeChecks)
                    val allocated = runtime("art.gc.bytes-allocated")
                    val gc = runtime("art.gc.gc-count")
                    val started = System.nanoTime()
                    repeat(100) { record() }
                    val elapsed = System.nanoTime() - started
                    val bytes = runtime("art.gc.bytes-allocated") - allocated
                    val collections = runtime("art.gc.gc-count") - gc
                    val after = listOf(intentGets, intentRows, pageRecords, returnedEvents, chapterCommits, wakeChecks)
                    check(after.zip(before) { a, b -> a - b } == listOf(200, 200, 100, 100, 100, 100))
                    check(bytes >= 0 && scheduled == 1)
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString(
                                "page_allocation",
                                "provider=komga;history=$history;sample=$sample;events=100;intent_get=200;" +
                                    "intent_rows=200;record=100;returned=100;chapter_commit=100;wake=100;" +
                                    "allocated_bytes=$bytes;gc=$collections;elapsed_ns=$elapsed",
                            )
                        },
                    )
                }
                val saved = checkNotNull(actualPages.get(key))
                check(saved.generation == sequence && saved.revision == token.revision && saved.readAt == sequence)
                check(saved.page == sequence % 999 && saved.total == 1000L && saved.baseline == encoded)
                check(saved.phase == KomgaReadStatusPhase.PENDING && actualPages.attempt(key) == null)
                check(actual.get(key) == originalIntent && actualPages.get(foreignKey) == foreign)
                check(actual.forOwner(id, "a") == beforeIntents)
                check(actualPages.states(id, "a", historyKeys) == beforePages)
            }
        } finally {
            actualPages.removeConnection(id)
            actual.removeConnection(id)
            check(actual.forOwner(id, "a").isEmpty() && actual.forOwner(id, "b").isEmpty())
            check(actualPages.get(key) == null && actualPages.get(foreignKey) == null)
            instrumentation.sendStatus(0, Bundle().apply { putString("page_allocation_cleanup", "komga") })
        }
    }

    private fun runtime(key: String) = checkNotNull(Debug.getRuntimeStat(key)?.toLongOrNull())
}

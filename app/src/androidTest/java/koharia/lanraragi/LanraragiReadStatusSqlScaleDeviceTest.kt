package koharia.lanraragi

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionReadStatus
import koharia.connection.LibraryConnectionProfile
import koharia.domain.lanraragi.LanraragiEntry
import koharia.domain.lanraragi.LanraragiReadState
import koharia.domain.lanraragi.LanraragiRepository
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LanraragiReadStatusSqlScaleDeviceTest {
    @Test
    fun batchMarksUseSingleResourceCatalogueQueries(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "app.koharia.dev.devicefixture")
        val selected = InstrumentationRegistry.getArguments().getString("producerScale")?.toInt()
        check(selected == null || selected in listOf(1, 100, 1000))
        for (count in selected?.let(::listOf) ?: listOf(1, 100, 1000)) {
            val actual = Injekt.get<LanraragiRepository>()
            val id = System.nanoTime()
            for (owner in listOf(id, id + 1)) {
                check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(owner))
                check(actual.entries(owner).isEmpty() && actual.readStates(owner).isEmpty())
            }
            var catalogueQueries = 0
            var catalogueRows = 0
            var entryQueries = 0
            var entryRows = 0
            var points = 0
            var writes = 0
            val counted = object : LanraragiRepository by actual {
                override suspend fun entry(connectionId: Long, resourceId: String): LanraragiEntry? {
                    check(connectionId == id)
                    entryQueries++
                    return actual.entry(connectionId, resourceId).also { if (it != null) entryRows++ }
                }
                override suspend fun entries(connectionId: Long): List<LanraragiEntry> {
                    check(connectionId == id)
                    catalogueQueries++
                    return actual.entries(connectionId).also { catalogueRows += it.size }
                }
                override suspend fun readState(connectionId: Long, archiveId: String): LanraragiReadState? {
                    check(connectionId == id)
                    points++
                    return actual.readState(connectionId, archiveId)
                }
                override suspend fun record(connectionId: Long, state: LanraragiReadState) {
                    check(connectionId == id)
                    actual.record(connectionId, state)
                    writes++
                }
            }
            val source = LanraragiSource(
                instrumentation.targetContext,
                LibraryConnectionProfile(id, "lanraragi", "Owned SQL scale"),
            )
            LanraragiSource::class.java.getDeclaredField("repository").apply {
                isAccessible = true
            }.set(source, counted)
            val foreign = LanraragiReadState("book-1", 4, 50, 12, pending = true)
            try {
                val entries = (1..1000).map { LanraragiEntry("book-$it", title = "Owned $it", pageCount = 10) }
                actual.stage(id, 1, entries)
                actual.publish(id, 1, 1)
                Injekt.get<Database>().transaction {
                    repeat(1000) { actual.record(id, LanraragiReadState("history-$it", 3, 10, 1, pending = false)) }
                    actual.record(id + 1, foreign)
                }
                val history = actual.readStates(id)
                val foreignBefore = actual.readStates(id + 1)
                val started = System.nanoTime()
                for (chapter in 1..count) {
                    val read = chapter % 2 == 0
                    check(
                        source.setChapterReadStatus("/lanraragi/$id/archive/book-$chapter", read) ==
                            if (read) ConnectionReadStatus.PENDING else ConnectionReadStatus.LOCAL_ONLY,
                    )
                }
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val after = actual.readStates(id)
                check(after.filter { it.archiveId.startsWith("history-") } == history)
                check(actual.readStates(id + 1) == foreignBefore)
                check(actual.entries(id).toSet() == entries.toSet())
                for (chapter in 1..count) {
                    val state = checkNotNull(actual.readState(id, "book-$chapter"))
                    val read = chapter % 2 == 0
                    check(state.pending == read && state.localUnread == !read)
                    check(state.totalPages == 10 && state.pageIndex == if (read) 9 else 0)
                    check(state.readAt > 0 && state.revision == 1L && !state.requiresConfirmation)
                }
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "lanraragi_producer",
                            "marks=$count;catalogue_queries=$catalogueQueries;catalogue_rows=$catalogueRows;" +
                                "entry_queries=$entryQueries;entry_rows=$entryRows;" +
                                "points=$points;writes=$writes;elapsed_ms=$elapsed",
                        )
                    },
                )
                check(points == count && writes == count)
                check(entryQueries == count && entryRows == count)
                check(catalogueQueries == 0 && catalogueRows == 0) { "Batch marks must not reload the catalogue" }
            } finally {
                source.close()
                actual.remove(id)
                actual.remove(id + 1)
                check(actual.entries(id).isEmpty() && actual.readStates(id).isEmpty())
                check(actual.readStates(id + 1).isEmpty())
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("lanraragi_producer_cleanup", count.toString())
                    },
                )
            }
        }
    }

    @Test
    fun singleEntryQueryUsesOnlyPublishedGenerationAndOwningConnection(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "app.koharia.dev.devicefixture")
        val actual = Injekt.get<LanraragiRepository>()
        val id = System.nanoTime()
        for (owner in listOf(id, id + 1)) {
            check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(owner))
            check(actual.entries(owner).isEmpty() && actual.readStates(owner).isEmpty())
        }
        val old = LanraragiEntry("book", title = "Published", pageCount = 10)
        val next = old.copy(title = "Next generation", pageCount = 20)
        val foreign = old.copy(title = "Foreign", pageCount = 30)
        try {
            actual.stage(id, 1, listOf(old))
            check(actual.entry(id, "book") == null)
            actual.publish(id, 1, 1)
            actual.stage(id, 2, listOf(next))
            actual.stage(id + 1, 1, listOf(foreign))
            actual.publish(id + 1, 1, 1)
            check(actual.entry(id, "book") == old)
            check(actual.entry(id + 1, "book") == foreign)
            check(actual.entry(id, "missing") == null)
            actual.publish(id, 2, 2)
            check(actual.entry(id, "book") == next)
            actual.stage(id, 3, listOf(old))
            actual.abort(id, 3)
            check(actual.entry(id, "book") == next)
            actual.publish(id, 4, 4)
            check(actual.entry(id, "book") == null && actual.entries(id).isEmpty())
            check(actual.entry(id + 1, "book") == foreign)
        } finally {
            actual.remove(id)
            actual.remove(id + 1)
            check(actual.entry(id, "book") == null && actual.entry(id + 1, "book") == null)
            instrumentation.sendStatus(0, Bundle().apply { putString("lanraragi_entry_cleanup", "complete") })
        }
    }
}

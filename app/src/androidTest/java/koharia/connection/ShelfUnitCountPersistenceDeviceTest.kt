package koharia.connection

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ShelfUnitCountPersistenceDeviceTest {
    @Test
    fun actualSqlKeepsUserFieldsScopesBatchesAndRollsBackObsoleteTotals(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "app.koharia.dev.devicefixture")
        val repository = Injekt.get<MangaRepository>()
        val own = -System.nanoTime()
        val other = own - 1
        check(repository.getMangaBySourceId(own).isEmpty() && repository.getMangaBySourceId(other).isEmpty())
        try {
            val seeds = repository.insertNetworkManga(
                (0..450).map { index ->
                    Manga.create().copy(
                        source = own,
                        url = "/series/$index",
                        favorite = true,
                        title = "Retained $index",
                        viewerFlags = 123,
                        memo = buildJsonObject {
                            put("booksCount", 2)
                            put("localMetadata", "keep")
                        },
                    )
                },
            )
            check(repository.updateAll(seeds.map { MangaUpdate(id = it.id, notes = "Retained note ${it.id}") }))
            val original = repository.getMangaBySourceId(own)
            val neighbour = repository.insertNetworkManga(
                listOf(original.first().copy(id = -1, source = other)),
            ).single()
            val remote = original.map {
                it.copy(
                    title = "Remote",
                    favorite = false,
                    viewerFlags = 0,
                    memo = buildJsonObject {
                        put("booksCount", 3)
                    },
                )
            }
            repository.refreshShelfUnitCounts(remote + remote.first().copy(url = "/never-materialized")) {}
            val current = repository.getMangaBySourceId(own).associateBy { it.id }
            check(current.size == original.size)
            for (before in original) {
                val after = current.getValue(before.id)
                check(after.memo["booksCount"] == JsonPrimitive(3))
                check(after.copy(memo = before.memo, lastModifiedAt = before.lastModifiedAt) == before)
            }
            check(repository.getMangaById(neighbour.id) == neighbour)
            val target = current.getValue(original.first().id)
            for (memo in listOf(
                JsonObject(emptyMap()),
                buildJsonObject { put("booksCount", -1) },
                buildJsonObject {
                    put("booksCount", "invalid")
                },
            )) {
                repository.refreshShelfUnitCounts(listOf(target.copy(memo = memo))) {}
                check(repository.getMangaById(target.id) == target)
            }
            var checks = 0
            var cancelled = false
            try {
                repository.refreshShelfUnitCounts(
                    listOf(target.copy(memo = buildJsonObject { put("booksCount", 9) })),
                ) {
                    if (++checks == 3) throw CancellationException("Fixture owner replaced before commit")
                }
            } catch (_: CancellationException) {
                cancelled = true
            }
            check(cancelled && checks == 3)
            check(repository.getMangaById(target.id) == target)
            repository.refreshShelfUnitCounts(listOf(target.copy(memo = buildJsonObject { put("booksCount", 0) }))) {}
            check(repository.getMangaById(target.id).memo["booksCount"] == JsonPrimitive(0))
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "shelf_unit_count_sql",
                        "rows=451;userFields=retained;foreign=unchanged;" +
                            "missing=not-inserted;rollback=true;zero=retained",
                    )
                },
            )
        } finally {
            withContext(NonCancellable) {
                repository.deleteMangaBySourceId(own)
                repository.deleteMangaBySourceId(other)
                check(repository.getMangaBySourceId(own).isEmpty() && repository.getMangaBySourceId(other).isEmpty())
                instrumentation.sendStatus(0, Bundle().apply { putString("shelf_unit_count_sql_cleanup", "complete") })
            }
        }
    }
}

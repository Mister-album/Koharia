package koharia.diagnostics

import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.track.TrackerManager
import koharia.connection.ConnectionPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LegacyTrackerBackupDeviceTest {
    @Test
    fun unregisteredTrackerDataSurvivesRestoreRepeatRestoreAndExport(): Unit = runBlocking(Dispatchers.IO) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "app.koharia.dev.devicefixture")
        val source = System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(source))
        val mangas = Injekt.get<MangaRepository>()
        check(mangas.getMangaBySourceId(source).isEmpty())
        val ids = listOf(1, 2, 3, 4, 5, 7, 8, 9, 987654)
        val manager = Injekt.get<TrackerManager>()
        assertEquals(listOf(TrackerManager.KOMGA), manager.trackers.map { it.id })
        ids.forEach { assertNull(manager.get(it.toLong())) }
        val tracking = ids.map { id ->
            BackupTracking(
                syncId = id,
                libraryId = id.toLong() + 100,
                mediaId = id.toLong() + Int.MAX_VALUE,
                title = "Legacy fixture $id",
                trackingUrl = "https://tracker.invalid/fixture/$id",
                lastChapterRead = 12.5f,
                totalChapters = 80,
                score = 7.5f,
                status = 3,
                startedReadingDate = 1000,
                finishedReadingDate = 2000,
                private = true,
            )
        }
        val original =
            BackupManga(
                source,
                "fixture-legacy-tracks",
                "Legacy tracker fixture",
                tracking = tracking,
                favorite = false,
            )
        val decoded = ProtoBuf.decodeFromByteArray(
            BackupManga.serializer(),
            ProtoBuf.encodeToByteArray(BackupManga.serializer(), original),
        )
        try {
            val restorer = MangaRestorer()
            restorer.restore(decoded, emptyList())
            val manga = checkNotNull(Injekt.get<GetMangaByUrlAndSourceId>().await(decoded.url, source))
            val getTracks = Injekt.get<GetTracks>()
            val expected = tracking.map { it.getTrackImpl().copy(id = 0, mangaId = manga.id) }.sortedBy { it.trackerId }
            assertEquals(expected, getTracks.await(manga.id).map { it.copy(id = 0) }.sortedBy { it.trackerId })
            val before = getTracks.await(manga.id).sortedBy { it.trackerId }
            restorer.restore(decoded, emptyList())
            assertEquals(before, getTracks.await(manga.id).sortedBy { it.trackerId })
            val exported = MangaBackupCreator()(
                listOf(manga),
                BackupOptions(chapters = false, categories = false, history = false),
            ).single()
            assertEquals(tracking.sortedBy { it.syncId }, exported.tracking.sortedBy { it.syncId })
        } finally {
            mangas.deleteMangaBySourceId(source)
        }
    }
}

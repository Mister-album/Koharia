package eu.kanade.domain.track.interactor

import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.usesAutomaticChapterCounts
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack

class SourceOwnedTrackingTest {
    private abstract class SourceTracker : Tracker, EnhancedTracker

    @Test
    fun `source-owned completion never refreshes writes or queues cumulative state`() = runTest {
        val service = mockk<eu.kanade.tachiyomi.data.track.komga.Komga>()
        every { service.usesSourceReadingProgress } returns true
        val manager = mockk<TrackerManager>()
        every { manager.get(6) } returns service
        val tracks = mockk<GetTracks>()
        coEvery { tracks.await(7) } returns listOf(track().toDomainTrack()!!)
        val insert = mockk<InsertTrack>()
        val delayed = mockk<DelayedTrackingStore>()
        TrackChapter(tracks, manager, insert, delayed).await(mockk(), 7, 8.0)
        coVerify(exactly = 0) { service.refresh(any()) }
        coVerify(exactly = 0) { service.update(any(), any()) }
        coVerify(exactly = 0) { insert.await(any()) }
        verify(exactly = 0) { delayed.add(any(), any()) }
        assertFalse(service.usesAutomaticChapterCounts())
        assertTrue(mockk<Tracker>().usesAutomaticChapterCounts())
    }

    @Test
    fun `refresh cannot mark earlier books read or push a cumulative count`() = runTest {
        val service = mockk<SourceTracker>()
        every { service.usesSourceReadingProgress } returns true
        val chapters = mockk<GetChaptersByMangaId>()
        val update = mockk<UpdateChapter>()
        val insert = mockk<InsertTrack>()
        SyncChapterProgressWithTrack(update, insert, chapters).await(7, track().toDomainTrack()!!, service)
        coVerify(exactly = 0) { chapters.await(any<Long>(), any()) }
        coVerify(exactly = 0) { update.awaitAll(any()) }
        coVerify(exactly = 0) { service.update(any(), any()) }
        coVerify(exactly = 0) { insert.await(any()) }
    }

    @Test
    fun `binding preserves the track without sending local counts or reading dates`() = runTest {
        val service = mockk<SourceTracker>()
        every { service.usesSourceReadingProgress } returns true
        coEvery { service.bind(any(), any()) } answers { firstArg() }
        val chapters = mockk<GetChaptersByMangaId>()
        coEvery { chapters.await(7) } returns listOf(Chapter.create().copy(read = true, chapterNumber = 10.0))
        val insert = mockk<InsertTrack>(relaxed = true)
        val sync = SyncChapterProgressWithTrack(mockk(), insert, chapters)
        AddTracks(insert, sync, chapters, mockk()).bind(service, track(), 7)
        coVerify(exactly = 1) { insert.await(any()) }
        coVerify(exactly = 0) { service.setRemoteLastChapterRead(any(), any()) }
        coVerify(exactly = 0) { service.setRemoteStartDate(any(), any()) }
        coVerify(exactly = 0) { service.update(any(), any()) }
    }

    @Test
    fun `legacy worker stops on durable quarantine and retries a failed commit`() = runTest {
        val store = mockk<DelayedTrackingStore>()
        every { store.holdLegacyItems() } returnsMany listOf(false, true)
        every { store.getHeldItems() } returns listOf(
            DelayedTrackingStore.HeldTrackingItem("fixture", "7", 8f, "unknown_owner"),
        )
        val worker = DelayedTrackingUpdateJob(mockk(relaxed = true), mockk(relaxed = true), store)
        assertEquals(androidx.work.ListenableWorker.Result.retry(), worker.doWork())
        assertEquals(
            androidx.work.ListenableWorker.Result.success(
                androidx.work.workDataOf(
                    "progressStatus" to "held",
                    "heldCount" to 1,
                ),
            ),
            worker.doWork(),
        )
        verify(exactly = 0) { store.getItems() }
    }

    private fun track() = Track.create(6).apply {
        id = 9
        manga_id = 7
        title = "Fixture"
        last_chapter_read = 0.0
        total_chapters = 10
        tracking_url = "https://fixture.invalid/api/v1/series/one"
    }
}

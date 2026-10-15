package koharia.komga

import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.track.komga.KomgaApi
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionPreferences
import koharia.domain.komga.KomgaPageEventRepository
import koharia.domain.komga.KomgaReadStatusPhase
import koharia.domain.komga.KomgaReadStatusRepository
import koharia.komga.progress.KomgaApiPageTransport
import koharia.komga.progress.KomgaPageDispatcher
import koharia.komga.progress.KomgaProgressBaseline
import koharia.komga.progress.KomgaProgressDispatchResult
import koharia.komga.progress.KomgaProgressOrdering
import koharia.source.komga.KomgaSource
import koharia.source.komga.captureKomgaProgressSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class KomgaPageDispatcherDeviceTest {
    @Test
    fun httpContentEventsReachChapterWorkerButOrganizationEventsDoNot(): Unit = fixture(registered = true) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wm = androidx.work.WorkManager.getInstance(context)
        val name = eu.kanade.tachiyomi.data.library.LibraryUpdateJob.workName(
            source.id,
            source.captureChapterOwner().key,
        )
        val mangas = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
        val chapters = Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
        val downloadPreference = Injekt.get<tachiyomi.domain.download.service.DownloadPreferences>().downloadNewChapters
        val oldDownloadPreference = downloadPreference.get()
        val manga = mangas.insertNetworkManga(
            listOf(
                tachiyomi.domain.manga.model.Manga.create().copy(
                    source = source.id,
                    url = "${server.root}/api/v1/books/book",
                    title = "SSE chapter fixture",
                    favorite = true,
                ),
            ),
        ).single()
        try {
            downloadPreference.set(false)
            val before = koharia.connection.ConnectionShelfUpdates.version(source.id)
            server.sseBody = "event: CollectionChanged\ndata: {\"collectionId\":\"fixture\"}\n\n"
            withSseClient {
                kotlinx.coroutines.withTimeout(10_000) {
                    while (koharia.connection.ConnectionShelfUpdates.version(source.id) == before) {
                        kotlinx.coroutines.delay(20)
                    }
                }
                assertEquals(1, server.sseReads.get())
                assertEquals(0, server.reads.get())
                assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
                assertTrue(chapters.getChapterByMangaId(manga.id).isEmpty())

                server.sseBody = "event: BookAdded\ndata: {\"bookId\":\"book\"}\n\n"
                kotlinx.coroutines.withContext(Dispatchers.Main) { reconnect() }
                kotlinx.coroutines.withTimeout(20_000) {
                    while (true) {
                        val work = wm.getWorkInfosForUniqueWork(name).get()
                        if (work.isNotEmpty() && work.all { it.state.isFinished }) {
                            assertTrue(work.all { it.state == androidx.work.WorkInfo.State.SUCCEEDED })
                            break
                        }
                        kotlinx.coroutines.delay(50)
                    }
                }
                assertEquals(2, server.sseReads.get())
                assertEquals(1, chapters.getChapterByMangaId(manga.id).size)
                assertTrue(server.reads.get() > 0)
                assertEquals(0, server.writes.get())
            }
        } finally {
            wm.cancelUniqueWork(name).result.get()
            context.getSystemService(android.app.NotificationManager::class.java).cancel(manga.id.hashCode())
            mangas.deleteMangaById(manga.id)
            downloadPreference.set(oldDownloadPreference)
        }
    }

    @Test
    fun httpContentEventFromObsoleteOwnerCannotScheduleChapterWorker(): Unit = fixture(registered = true) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wm = androidx.work.WorkManager.getInstance(context)
        val oldOwner = source.captureChapterOwner().key
        val name = eu.kanade.tachiyomi.data.library.LibraryUpdateJob.workName(source.id, oldOwner)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val before = koharia.connection.ConnectionShelfUpdates.version(source.id)
        server.sseBody = "event: BookUpdated\ndata: {\"bookId\":\"book\"}\n\n"
        server.onSse = {
            entered.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        try {
            withSseClient { observer ->
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                val ongoing = observer.children.toSet()
                check(sourcePreferences("source_${source.id}").edit().putString("Username", "replacement").commit())
                assertTrue(source.captureChapterOwner().key != oldOwner)
                release.countDown()
                kotlinx.coroutines.withTimeout(10_000) {
                    while (koharia.connection.ConnectionShelfUpdates.version(source.id) == before) {
                        kotlinx.coroutines.delay(20)
                    }
                }
                kotlinx.coroutines.withTimeout(10_000) {
                    observer.children.filterNot { it in ongoing }.forEach { it.join() }
                }
                val newName = eu.kanade.tachiyomi.data.library.LibraryUpdateJob.workName(
                    source.id,
                    source.captureChapterOwner().key,
                )
                assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
                assertTrue(wm.getWorkInfosForUniqueWork(newName).get().isEmpty())
                assertEquals(0, server.reads.get())
                assertEquals(0, server.writes.get())
            }
        } finally {
            release.countDown()
            wm.cancelUniqueWork(name).result.get()
        }
    }

    private suspend fun Fixture.withSseClient(
        block: suspend koharia.komga.api.KomgaSseClient.(kotlinx.coroutines.Job) -> Unit,
    ) {
        val scopeJob = kotlinx.coroutines.SupervisorJob()
        val scope = kotlinx.coroutines.CoroutineScope(scopeJob + Dispatchers.Main.immediate)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = koharia.komga.api.KomgaSseClient(
            context,
            Injekt.get(),
            lazy { Injekt.get() },
            { source.baseUrl },
            { source.id },
            { source.currentReadiumHeaders() },
            { false },
            { source.client },
        )
        val lifecycle = androidx.lifecycle.ProcessLifecycleOwner.get()
        try {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                client.start(scope)
                client.onStart(lifecycle)
            }
            client.block(scopeJob)
        } finally {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                client.onStop(lifecycle)
                lifecycle.lifecycle.removeObserver(client)
            }
            scopeJob.cancel()
            scopeJob.join()
        }
    }

    @Test
    fun connectionRefreshKeepsChapterNotificationsAndCoalescesContentEvents(): Unit = fixture(registered = true) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wm = androidx.work.WorkManager.getInstance(context)
        val owner = source.captureChapterOwner()
        val name = eu.kanade.tachiyomi.data.library.LibraryUpdateJob.workName(source.id, owner.key)
        val mangas = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
        val chapters = Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
        val downloads = Injekt.get<tachiyomi.domain.download.service.DownloadPreferences>().downloadNewChapters
        val oldDownloads = downloads.get()
        val notifications = context.getSystemService(android.app.NotificationManager::class.java)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val entries = mangas.insertNetworkManga(
            listOf(source.id, source.id + 1).map { id ->
                tachiyomi.domain.manga.model.Manga.create().copy(
                    source = id,
                    url = "${server.root}/api/v1/books/book",
                    title = "Chapter refresh fixture $id",
                    favorite = true,
                )
            },
        )
        try {
            downloads.set(false)
            assertTrue("Fixture notifications must be enabled", notifications.areNotificationsEnabled())
            assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
            assertEquals(0, server.reads.get())
            assertTrue(
                !eu.kanade.tachiyomi.data.library.LibraryUpdateJob.request(
                    source,
                    koharia.connection.ChapterFetchOrigin.SERVER_UPDATE,
                    expectedOwner = "obsolete-owner",
                ),
            )
            server.onGet = {
                entered.countDown()
                check(release.await(15, java.util.concurrent.TimeUnit.SECONDS))
            }
            assertTrue(
                eu.kanade.tachiyomi.data.library.LibraryUpdateJob.request(
                    source,
                    koharia.connection.ChapterFetchOrigin.EXPLICIT_REFRESH,
                ),
            )
            assertTrue(entered.await(15, java.util.concurrent.TimeUnit.SECONDS))
            val requests = kotlinx.coroutines.coroutineScope {
                (1..8).map {
                    async {
                        eu.kanade.tachiyomi.data.library.LibraryUpdateJob.request(
                            source,
                            koharia.connection.ChapterFetchOrigin.SERVER_UPDATE,
                        )
                    }
                }.map { it.await() }
            }
            assertEquals(1, requests.count { it })
            val queued = wm.getWorkInfosForUniqueWork(name).get()
            assertEquals(2, queued.size)
            assertEquals(1, queued.count { it.state == androidx.work.WorkInfo.State.RUNNING })
            release.countDown()
            kotlinx.coroutines.withTimeout(20_000) {
                while (wm.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }) {
                    kotlinx.coroutines.delay(50)
                }
            }
            assertTrue(
                wm.getWorkInfosForUniqueWork(name).get().all {
                    it.state == androidx.work.WorkInfo.State.SUCCEEDED
                },
            )
            assertEquals(1, chapters.getChapterByMangaId(entries[0].id).size)
            assertTrue(chapters.getChapterByMangaId(entries[1].id).isEmpty())
            assertTrue(notifications.activeNotifications.any { it.id == entries[0].id.hashCode() })
            assertEquals(0, server.writes.get())
        } finally {
            release.countDown()
            wm.cancelUniqueWork(name).result.get()
            entries.forEach {
                notifications.cancel(it.id.hashCode())
                mangas.deleteMangaById(it.id)
            }
            downloads.set(oldDownloads)
        }
    }

    @Test
    fun retirementCancelsOnlyLegacyNamesAndDoesNotRecreatePolling(): Unit = fixture(registered = true) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wm = androidx.work.WorkManager.getInstance(context)
        val names = listOf("LibraryUpdate-auto", "LibraryUpdate-manual")
        names.forEach { name -> check(wm.getWorkInfosForUniqueWork(name).get().all { it.state.isFinished }) }
        val periodic = androidx.work.PeriodicWorkRequestBuilder<eu.kanade.tachiyomi.data.library.LibraryUpdateJob>(
            1,
            java.util.concurrent.TimeUnit.DAYS,
        ).setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS).build()
        val manual = androidx.work.OneTimeWorkRequestBuilder<eu.kanade.tachiyomi.data.library.LibraryUpdateJob>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS).build()
        val unrelated = androidx.work.OneTimeWorkRequestBuilder<eu.kanade.tachiyomi.data.library.LibraryUpdateJob>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS).build()
        try {
            wm.enqueueUniquePeriodicWork(names[0], androidx.work.ExistingPeriodicWorkPolicy.KEEP, periodic).result.get()
            wm.enqueueUniqueWork(names[1], androidx.work.ExistingWorkPolicy.KEEP, manual).result.get()
            wm.enqueue(unrelated).result.get()
            eu.kanade.tachiyomi.data.library.LibraryUpdateJob.setupTask(context, prefInterval = 12)
            assertTrue(!eu.kanade.tachiyomi.data.library.LibraryUpdateJob.startNow(context))
            kotlinx.coroutines.withTimeout(5_000) {
                while (listOf(periodic.id, manual.id).any {
                        wm.getWorkInfoById(it).get()!!.state != androidx.work.WorkInfo.State.CANCELLED
                    }
                ) {
                    kotlinx.coroutines.delay(50)
                }
            }
            assertEquals(androidx.work.WorkInfo.State.ENQUEUED, wm.getWorkInfoById(unrelated.id).get()!!.state)
            names.forEach { name -> assertTrue(wm.getWorkInfosForUniqueWork(name).get().all { it.state.isFinished }) }
            assertEquals(0, server.reads.get())
        } finally {
            listOf(periodic.id, manual.id, unrelated.id).forEach { wm.cancelWorkById(it).result.get() }
        }
    }

    @Test
    fun retiredAndObsoleteChapterWorkersDoNotFetch(): Unit = fixture(registered = true) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wm = androidx.work.WorkManager.getInstance(context)
        val requests = listOf(
            androidx.work.OneTimeWorkRequestBuilder<eu.kanade.tachiyomi.data.library.LibraryUpdateJob>().build(),
            androidx.work.OneTimeWorkRequestBuilder<eu.kanade.tachiyomi.data.library.LibraryUpdateJob>()
                .setInputData(
                    androidx.work.workDataOf(
                        "connection" to source.id,
                        "owner" to "obsolete-owner",
                        "origin" to koharia.connection.ChapterFetchOrigin.EXPLICIT_REFRESH.name,
                    ),
                ).build(),
        )
        try {
            wm.enqueue(requests).result.get()
            kotlinx.coroutines.withTimeout(15_000) {
                while (requests.any { !wm.getWorkInfoById(it.id).get()!!.state.isFinished }) {
                    kotlinx.coroutines.delay(50)
                }
            }
            assertTrue(
                requests.all {
                    wm.getWorkInfoById(it.id).get()!!.state == androidx.work.WorkInfo.State.SUCCEEDED
                },
            )
            assertEquals(0, server.reads.get())
            assertEquals(0, server.writes.get())
        } finally {
            requests.forEach { wm.cancelWorkById(it.id).result.get() }
        }
    }

    @Test
    fun productionReviewReissuesUncertainReadAndUnreadThroughDurableWorker() {
        for (epub in listOf(false, true)) {
            for (read in listOf(false, true)) {
                fixture(registered = true) {
                    server.epub = epub
                    server.page = 3
                    val desired = mark(read)
                    server.loseReply = true
                    server.applyWrite = false
                    assertTrue(runCatching { dispatchIntent() }.isFailure)
                    val request = checkNotNull(intents.attempt(key))
                    server.loseReply = false
                    server.applyWrite = true
                    val url = "${server.root}/api/v1/books/book"
                    val review = checkNotNull(source.reviewReadStatus(url))
                    assertEquals(read, review.localRead)
                    assertTrue(review.remoteStarted && !review.remoteRead && review.uncertainRequest)
                    assertEquals(1, server.writes.get())
                    assertEquals(desired, intents.get(key))
                    assertEquals(request, intents.attempt(key))
                    review.reissue()
                    kotlinx.coroutines.withTimeout(15_000) {
                        while (intents.get(key)?.phase != KomgaReadStatusPhase.SYNCED) kotlinx.coroutines.delay(50)
                    }
                    assertEquals(desired.revision + 1, intents.get(key)?.revision)
                    assertEquals(read, server.completed)
                    if (!read) assertNull(server.page)
                    assertEquals(2, server.writes.get())
                    assertNull(intents.attempt(key))
                    assertTrue(!intents.settle(request, KomgaReadStatusPhase.SYNCED, "late confirmation") {})
                }
            }
        }
    }

    @Test
    fun productionReviewRejectsReplacedCredentialsAndForeignDestinations(): Unit = fixture(registered = true) {
        val desired = mark(true)
        val url = "${server.root}/api/v1/books/book"
        val reviewed = checkNotNull(source.reviewReadStatus(url))
        val reads = server.reads.get()
        assertTrue(runCatching { source.reviewReadStatus("https://different.invalid/api/v1/books/book") }.isFailure)
        check(sourcePreferences("source_${source.id}").edit().putString("Password", "fixture-changed").commit())
        assertTrue(runCatching { reviewed.reissue() }.isFailure)
        assertEquals(desired, intents.get(key))
        assertEquals(reads, server.reads.get())
        assertEquals(0, server.writes.get())
    }

    @Test
    fun productionSyncStatusIsLocalAndRetainsOtherOwnersAsUnverified(): Unit = fixture(registered = true) {
        val url = "${server.root}/api/v1/books/book"
        suspend fun state() = source.observeProgressSyncStatus(listOf(url)).first()
        assertTrue(state().isEmpty())
        pages.record(key, 0, 2, 10, 100, null) {}
        assertEquals(setOf(koharia.connection.ConnectionProgressSyncStatus.PENDING), state()[url])
        pages.record(key.copy(ownerKey = "restored:status-fixture"), 0, 4, 10, 100, null) {}
        assertEquals(
            setOf(
                koharia.connection.ConnectionProgressSyncStatus.PENDING,
                koharia.connection.ConnectionProgressSyncStatus.UNVERIFIED_OWNER,
            ),
            state()[url],
        )
        assertEquals(0, server.reads.get())
        assertEquals(0, server.writes.get())
    }

    @Test
    fun missingBookHoldsPageAndExplicitIntentWithoutSending() {
        for (status in listOf(404, 410)) {
            fixture {
                val page = record(3)
                server.getStatus = status
                assertEquals(KomgaProgressDispatchResult.HELD, dispatch())
                assertEquals(page.copy(phase = KomgaReadStatusPhase.HELD), pages.get(key))
                assertNull(pages.attempt(key))
                server.getStatus = 200
                assertEquals(KomgaProgressDispatchResult.HELD, dispatch())
                assertEquals(0, server.writes.get())
            }
            fixture {
                val intent = mark(true)
                server.getStatus = status
                assertEquals(KomgaProgressDispatchResult.HELD, dispatchIntent())
                assertEquals(intent.copy(phase = KomgaReadStatusPhase.HELD), intents.get(key))
                assertNull(intents.attempt(key))
                server.getStatus = 200
                assertEquals(KomgaProgressDispatchResult.HELD, dispatchIntent())
                assertEquals(0, server.writes.get())
            }
        }
    }

    @Test
    fun missingBookDuringRecoveryRetainsBothKindsOfUncertainRequest() {
        for (status in listOf(404, 410)) {
            fixture {
                val page = record(3)
                server.loseReply = true
                assertTrue(runCatching { dispatch() }.isFailure)
                val request = checkNotNull(pages.attempt(key))
                intents.record(key, false, 101, page.baseline, emptyList()) {}
                server.getStatus = status
                assertEquals(KomgaProgressDispatchResult.HELD, drain())
                assertEquals(request, pages.attempt(key))
                // The newer explicit revision owns the hold; its obsolete page event stays unchanged.
                assertEquals(page, pages.get(key))
                assertEquals(KomgaReadStatusPhase.HELD, intents.get(key)?.phase)
                assertEquals(false, intents.get(key)?.read)
                assertTrue(key !in pages.recoveryKeys())
                assertEquals(1, server.writes.get())
            }
            fixture {
                mark(true)
                server.loseReply = true
                assertTrue(runCatching { dispatchIntent() }.isFailure)
                val request = checkNotNull(intents.attempt(key))
                server.getStatus = status
                assertEquals(KomgaProgressDispatchResult.HELD, drain())
                assertEquals(request, intents.attempt(key))
                assertEquals(KomgaReadStatusPhase.HELD, intents.get(key)?.phase)
                assertEquals(1, server.writes.get())
            }
        }
    }

    @Test
    fun missingWriteResponseRetainsRequestAndNeverBlindlyResends() {
        for (status in listOf(404, 410)) {
            fixture {
                record(3)
                server.patchStatus = status
                assertEquals(KomgaProgressDispatchResult.HELD, dispatch())
                val request = checkNotNull(pages.attempt(key))
                server.patchStatus = 200
                assertEquals(KomgaProgressDispatchResult.AWAITING_CONFIRMATION, drain())
                assertEquals(request, pages.attempt(key))
                assertEquals(1, server.writes.get())
            }
            fixture {
                mark(true)
                server.patchStatus = status
                assertEquals(KomgaProgressDispatchResult.HELD, dispatchIntent())
                val request = checkNotNull(intents.attempt(key))
                server.patchStatus = 200
                assertEquals(KomgaProgressDispatchResult.AWAITING_CONFIRMATION, drain())
                assertEquals(request, intents.attempt(key))
                assertEquals(1, server.writes.get())
            }
        }
    }

    @Test
    fun missingBookFinishesProductionWorkerWithoutRetry(): Unit = fixture(registered = true) {
        val intent = mark(true)
        server.getStatus = 404
        Injekt.get<koharia.komga.progress.KomgaProgressWakeups>().wake(key)
        val manager = androidx.work.WorkManager.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val work = kotlinx.coroutines.withTimeout(15_000) {
            while (true) {
                val jobs = manager.getWorkInfosForUniqueWork(koharia.komga.progress.KomgaProgressWorkManager.name(key))
                    .get()
                if (jobs.isNotEmpty() && jobs.all { it.state.isFinished }) return@withTimeout jobs
                kotlinx.coroutines.delay(50)
            }
            error("unreachable")
        }
        assertEquals(listOf(androidx.work.WorkInfo.State.SUCCEEDED), work.map { it.state })
        assertEquals("HELD", work.single().outputData.getString("progressStatus"))
        assertEquals(intent.copy(phase = KomgaReadStatusPhase.HELD), intents.get(key))
        assertTrue(key !in pages.recoveryKeys())
        assertEquals(0, server.writes.get())
    }

    @Test
    fun transientReadFailuresStayRetryableWithoutDroppingPendingState(): Unit = fixture {
        val page = record(3)
        server.getStatus = 503
        assertTrue(runCatching { dispatch() }.exceptionOrNull() is eu.kanade.tachiyomi.network.HttpException)
        assertEquals(page, pages.get(key))
        assertEquals(0, server.writes.get())
    }

    @Test
    fun foreignDestinationCannotAdoptAnExistingBookJournal(): Unit = fixture(registered = true) {
        val existing = record(2)
        val foreign = "https://foreign.invalid/api/v1/books/book"
        assertTrue(
            runCatching {
                koharia.komga.progress.captureKomgaPageProgressSession(
                    source,
                    foreign,
                    sources,
                    Injekt.get(),
                    Injekt.get(),
                    Injekt.get(),
                )
            }.exceptionOrNull() is koharia.source.komga.KomgaProgressOwnerChanged,
        )
        assertTrue(
            runCatching { source.setChapterReadStatus(foreign, true) }.exceptionOrNull() is
                koharia.source.komga.KomgaProgressOwnerChanged,
        )
        assertEquals(existing, pages.get(key))
        assertNull(intents.get(key))
        assertEquals(0, server.writes.get())
    }

    @Test
    fun productionExplicitUnreadCommitsAllAliasesBeforeSupersedingPendingPages(): Unit = fixture(registered = true) {
        val mangas = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
        val chapters = Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
        val url = "${server.root}/api/v1/books/book"
        val owners = mangas.insertNetworkManga(
            (1..2).map {
                tachiyomi.domain.manga.model.Manga.create().copy(
                    source = source.id,
                    url = "${server.root}/series/$it",
                    title = "Explicit fixture $it",
                )
            },
        )
        try {
            val aliases = chapters.addAll(
                owners.map {
                    tachiyomi.domain.chapter.model.Chapter.create().copy(
                        mangaId = it.id,
                        url = url,
                        name = "book",
                        read = true,
                        lastPageRead = 4,
                    )
                },
            )
            server.page = 5
            record(2)
            val result = Injekt.get<eu.kanade.domain.chapter.interactor.SetReadStatus>().await(false, aliases.first())
                as eu.kanade.domain.chapter.interactor.SetReadStatus.Result.Completed
            assertEquals(koharia.connection.ConnectionReadStatus.PENDING, result.chapters[aliases.first().id])
            aliases.forEach {
                assertEquals(false, chapters.getChapterById(it.id)?.read)
                assertEquals(0L, chapters.getChapterById(it.id)?.lastPageRead)
            }
            assertNull(pages.record(key, 0, 9, 10, 101, null) {})
            kotlinx.coroutines.withTimeout(15_000) {
                while (intents.get(key)?.phase != KomgaReadStatusPhase.SYNCED) kotlinx.coroutines.delay(50)
            }
            assertNull(server.page)
            assertEquals(1, server.writes.get())
            assertEquals(intents.get(key)?.revision, pages.get(key)?.supersededByRevision)
        } finally {
            owners.forEach { mangas.deleteMangaById(it.id) }
        }
    }

    @Test
    fun unknownExplicitBaselineIsHeldAndDeliberateReissueCanResume(): Unit = fixture(registered = true) {
        val url = "${server.root}/api/v1/books/book"
        server.getStatus = 503
        assertEquals(koharia.connection.ConnectionReadStatus.HELD, source.setChapterReadStatus(url, true))
        assertEquals(KomgaReadStatusPhase.HELD, intents.get(key)?.phase)
        assertEquals(true, intents.get(key)?.read)
        assertEquals(0, server.writes.get())
        server.getStatus = 200
        assertEquals(koharia.connection.ConnectionReadStatus.PENDING, source.setChapterReadStatus(url, true))
        kotlinx.coroutines.withTimeout(15_000) {
            while (intents.get(key)?.phase != KomgaReadStatusPhase.SYNCED) kotlinx.coroutines.delay(50)
        }
        assertEquals(2L, intents.get(key)?.revision)
        assertTrue(server.completed)
        assertEquals(1, server.writes.get())
    }

    @Test
    fun automaticComparisonAndFailedChapterCommitCannotEraseAnUncertainRequest(): Unit = fixture {
        record(2)
        server.loseReply = true
        assertTrue(runCatching { dispatch() }.isFailure)
        val request = checkNotNull(pages.attempt(key))
        val url = "${server.root}/api/v1/books/book"
        val adapter = koharia.komga.progress.captureKomgaPageProgressSession(
            source,
            url,
            sources,
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
        ).adapter
        val remote = checkNotNull(adapter.pullPageProgress(url, kotlinx.serialization.json.JsonObject(emptyMap())))
        assertTrue(remote.blocksAutomaticSelection)
        assertTrue(remote.uncertainRequest)
        for (decision in listOf(
            koharia.connection.ConnectionPageProgressDecision.COMPARED,
            koharia.connection.ConnectionPageProgressDecision.USE_REMOTE,
            koharia.connection.ConnectionPageProgressDecision.KEEP_LOCAL,
        )) {
            assertTrue(
                runCatching {
                    adapter.confirmPageComparison(url, remote, 2, 10, 100, decision) { error("must not commit") }
                }.isFailure,
            )
            assertEquals(request, pages.attempt(key))
        }
        assertEquals(1, server.writes.get())
    }

    @Test
    fun explicitReaderChoiceResolvesUncertainPageWithoutAcknowledgingOldRequest() {
        for (keepLocal in listOf(false, true)) {
            fixture(registered = true) {
                server.page = 5
                val old = record(2)
                server.applyWrite = false
                server.loseReply = true
                assertTrue(runCatching { dispatch() }.isFailure)
                val request = checkNotNull(pages.attempt(key))
                server.applyWrite = true
                server.loseReply = false
                val url = "${server.root}/api/v1/books/book"
                val adapter = koharia.komga.progress.captureKomgaPageProgressSession(
                    source,
                    url,
                    sources,
                    Injekt.get(),
                    Injekt.get(),
                    Injekt.get(),
                ).adapter
                val snapshot =
                    checkNotNull(adapter.pullPageProgress(url, kotlinx.serialization.json.JsonObject(emptyMap())))
                assertTrue(snapshot.uncertainRequest && snapshot.blocksAutomaticSelection)
                var committed = false
                adapter.confirmPageComparison(
                    url,
                    snapshot,
                    if (keepLocal) 2 else 4,
                    10,
                    101,
                    if (keepLocal) {
                        koharia.connection.ConnectionPageProgressDecision.KEEP_LOCAL
                    } else {
                        koharia.connection.ConnectionPageProgressDecision.USE_REMOTE
                    },
                ) { committed = true }
                assertTrue(committed)
                kotlinx.coroutines.withTimeout(15_000) {
                    while (pages.get(key)?.phase != KomgaReadStatusPhase.SYNCED) kotlinx.coroutines.delay(50)
                }
                val current = checkNotNull(pages.get(key))
                assertEquals(old.generation + 1, current.generation)
                assertEquals(if (keepLocal) 2L else 4L, current.page)
                assertEquals(if (keepLocal) 3 else 5, server.page)
                assertEquals(if (keepLocal) 2 else 1, server.writes.get())
                assertNull(pages.attempt(key))
                assertTrue(!pages.settle(request, KomgaReadStatusPhase.SYNCED, current.baseline) {})
                assertEquals(current, pages.get(key))
            }
        }
    }

    @Test
    fun readerChoiceRejectsChangedServerAndLocalPagesBeforeRetiringRequest(): Unit = fixture {
        server.page = 5
        val old = record(2)
        server.applyWrite = false
        server.loseReply = true
        assertTrue(runCatching { dispatch() }.isFailure)
        val request = checkNotNull(pages.attempt(key))
        server.loseReply = false
        val url = "${server.root}/api/v1/books/book"
        val adapter = koharia.komga.progress.captureKomgaPageProgressSession(
            source,
            url,
            sources,
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
        ).adapter
        val snapshot = checkNotNull(adapter.pullPageProgress(url, kotlinx.serialization.json.JsonObject(emptyMap())))
        server.page = 7
        server.modified = "other-client"
        assertTrue(
            runCatching {
                adapter.confirmPageComparison(
                    url,
                    snapshot,
                    2,
                    10,
                    101,
                    koharia.connection.ConnectionPageProgressDecision.KEEP_LOCAL,
                ) { error("Changed server must not commit local state") }
            }.isFailure,
        )
        assertEquals(old, pages.get(key))
        assertEquals(request, pages.attempt(key))
        val fresh = checkNotNull(adapter.pullPageProgress(url, kotlinx.serialization.json.JsonObject(emptyMap())))
        val newer = checkNotNull(pages.record(key, 0, 8, 10, 102, old.baseline) {})
        assertTrue(
            runCatching {
                adapter.confirmPageComparison(
                    url,
                    fresh,
                    2,
                    10,
                    103,
                    koharia.connection.ConnectionPageProgressDecision.KEEP_LOCAL,
                ) { error("Changed local state must not commit") }
            }.isFailure,
        )
        assertEquals(newer, pages.get(key))
        assertEquals(request, pages.attempt(key))
        assertEquals(1, server.writes.get())
    }

    @Test
    fun readerJournalRequiresCurrentComparisonAndRetainsNewConfirmedBaseline(): Unit = fixture {
        val url = "${server.root}/api/v1/books/book"
        val adapter = koharia.komga.progress.captureKomgaPageProgressSession(
            source,
            url,
            sources,
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
        ).adapter
        var committed = false
        adapter.commitLocalPageProgress(url, 0, 10, 100, true) { committed = true }
        assertTrue(committed)
        assertNull(pages.get(key))
        val stale = checkNotNull(adapter.pullPageProgress(url, kotlinx.serialization.json.JsonObject(emptyMap())))
        adapter.commitLocalPageProgress(url, 2, 10, 101, false) {}
        val held = checkNotNull(pages.get(key))
        assertEquals(KomgaReadStatusPhase.HELD, held.phase)
        assertTrue(
            runCatching {
                adapter.confirmPageComparison(
                    url,
                    stale,
                    2,
                    10,
                    102,
                    koharia.connection.ConnectionPageProgressDecision.KEEP_LOCAL,
                ) {
                }
            }.isFailure,
        )
        val fresh = checkNotNull(adapter.pullPageProgress(url, kotlinx.serialization.json.JsonObject(emptyMap())))
        assertTrue(fresh.blocksAutomaticSelection)
        assertTrue(
            runCatching {
                adapter.confirmPageComparison(
                    url,
                    fresh,
                    2,
                    10,
                    102,
                    koharia.connection.ConnectionPageProgressDecision.KEEP_LOCAL,
                ) {
                    error("chapter commit failed")
                }
            }.isFailure,
        )
        assertEquals(held, pages.get(key))
        adapter.confirmPageComparison(
            url,
            fresh,
            2,
            10,
            103,
            koharia.connection.ConnectionPageProgressDecision.KEEP_LOCAL,
        ) {
        }
        assertEquals(KomgaProgressDispatchResult.SYNCED, drain())
        val first = checkNotNull(pages.get(key))
        adapter.pushPageProgress(url, 9, 10)
        assertEquals(first, pages.get(key))
        adapter.commitLocalPageProgress(url, 4, 10, 104, false) {}
        assertEquals(first.baseline, pages.get(key)?.baseline)
        assertEquals(KomgaProgressDispatchResult.SYNCED, drain())
        assertEquals(2, server.writes.get())
        assertEquals(5, server.page)
    }

    @Test
    fun productionReaderAdapterPersistsBeforeWorkManagerSends(): Unit = fixture(registered = true) {
        val url = "${server.root}/api/v1/books/book"
        val adapter = koharia.komga.progress.captureKomgaPageProgressSession(
            source,
            url,
            sources,
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
        ).adapter
        val compared = checkNotNull(adapter.pullPageProgress(url, kotlinx.serialization.json.JsonObject(emptyMap())))
        adapter.confirmPageComparison(
            url,
            compared,
            8,
            10,
            100,
            koharia.connection.ConnectionPageProgressDecision.COMPARED,
        ) {
        }
        assertEquals(0L, pages.get(key)?.page)
        adapter.commitLocalPageProgress(url, 3, 10, 101, false) {
            assertEquals(3L, pages.get(key)?.page)
            assertEquals(0, server.writes.get())
        }
        kotlinx.coroutines.withTimeout(15_000) {
            while (pages.get(key)?.phase != KomgaReadStatusPhase.SYNCED) kotlinx.coroutines.delay(50)
        }
        assertEquals(1, server.writes.get())
        assertEquals(4, server.page)
    }

    @Test
    fun coordinatorRecoversOldPageBeforeSendingLatestExplicitIntent(): Unit = fixture {
        val old = record(3)
        server.loseReply = true
        assertTrue(runCatching { dispatch() }.isFailure)
        intents.record(key, false, 101, old.baseline, emptyList()) {}
        server.loseReply = false
        assertEquals(KomgaProgressDispatchResult.SYNCED, drain())
        assertEquals(2, server.writes.get())
        assertNull(server.page)
        assertTrue(!pages.hasUnsettled(key.connectionId, key.ownerKey))
    }

    @Test
    fun coordinatorBoundsContinuousReadingAndLaterWakeupFinishesLatestGeneration(): Unit = fixture {
        val first = record(1)
        server.onPatch = {
            runBlocking {
                val current = checkNotNull(pages.get(key))
                pages.record(key, 0, (current.page + 1) % 8, 10, current.readAt + 1, first.baseline) {}
            }
        }
        assertEquals(KomgaProgressDispatchResult.PENDING, drain())
        assertEquals(4, server.writes.get())
        assertEquals(5L, pages.get(key)?.generation)
        server.onPatch = {}
        assertEquals(KomgaProgressDispatchResult.SYNCED, drain())
        assertEquals(5, server.writes.get())
    }

    @Test
    fun workManagerCoalescesConcurrentWakeupsWithoutCancellingRunningRequests(): Unit = fixture(registered = true) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = androidx.work.WorkManager.getInstance(context)
        val backend = koharia.komga.progress.KomgaProgressWorkManager(manager)
        val wakeups = Injekt.get<koharia.komga.progress.KomgaProgressWakeups>()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val name = koharia.komga.progress.KomgaProgressWorkManager.name(key)
        val first = record(1)
        server.onPatch = {
            entered.countDown()
            check(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
        }
        try {
            assertTrue(wakeups.wake(key))
            assertTrue(entered.await(15, java.util.concurrent.TimeUnit.SECONDS))
            pages.record(key, 0, 5, 10, 101, first.baseline) {}
            kotlinx.coroutines.coroutineScope {
                (0..31).map { async { wakeups.wake(key) } }.forEach { it.await() }
            }
            val states = backend.states(key)
            assertEquals(1, states.count { it == androidx.work.WorkInfo.State.RUNNING })
            assertEquals(1, states.count { it == androidx.work.WorkInfo.State.BLOCKED })
            release.countDown()
            kotlinx.coroutines.withTimeout(20_000) {
                while (backend.states(key).isNotEmpty()) kotlinx.coroutines.delay(50)
            }
            assertEquals(2, server.writes.get())
            assertEquals(6, server.page)
            assertEquals(KomgaReadStatusPhase.SYNCED, pages.get(key)?.phase)
            assertEquals(2, manager.getWorkInfosByTag(name).get().size)
        } finally {
            release.countDown()
            manager.cancelUniqueWork(name).result.get()
        }
    }

    @Test
    fun explicitReadAndUnreadPersistConfirmedBaselines(): Unit = fixture {
        mark(true)
        assertEquals(KomgaProgressDispatchResult.SYNCED, dispatchIntent())
        assertTrue(server.completed)
        assertEquals("revision-1", KomgaProgressBaseline.decode(intents.get(key)?.baseline)?.progressModified)
        mark(false)
        assertEquals(KomgaProgressDispatchResult.SYNCED, dispatchIntent())
        assertNull(server.page)
        assertTrue(!server.completed)
        assertEquals(false, KomgaProgressBaseline.decode(intents.get(key)?.baseline)?.progressPresent)
        assertEquals(2, server.writes.get())
    }

    @Test
    fun nativeEpubExplicitStatusDoesNotRequireFabricatedComicPages(): Unit = fixture {
        server.total = 0
        server.epub = true
        mark(true)
        assertEquals(KomgaProgressDispatchResult.SYNCED, dispatchIntent())
        assertNull(server.page)
        mark(false)
        assertEquals(KomgaProgressDispatchResult.SYNCED, dispatchIntent())
        assertEquals(2, server.writes.get())
    }

    @Test
    fun lostExplicitResponsesRecoverWithoutRepeatingPatchOrDelete() {
        for (read in listOf(true, false)) {
            fixture {
                server.completed = !read
                mark(read)
                server.loseReply = true
                assertTrue(runCatching { dispatchIntent() }.isFailure)
                assertNotNull(intents.attempt(key))
                assertEquals(KomgaProgressDispatchResult.SYNCED, dispatchIntent())
                assertNull(intents.attempt(key))
                assertEquals(1, server.writes.get())
            }
        }
    }

    @Test
    fun unchangedRemoteAfterExplicitRequestKeepsBothNewAndOldIntent(): Unit = fixture {
        val old = mark(true)
        server.loseReply = true
        server.applyWrite = false
        assertTrue(runCatching { dispatchIntent() }.isFailure)
        val newer = intents.record(key, false, 99, old.baseline, emptyList()) {}
        repeat(3) { assertEquals(KomgaProgressDispatchResult.AWAITING_CONFIRMATION, dispatchIntent()) }
        assertEquals(old, intents.attempt(key)?.intent)
        assertEquals(newer, intents.get(key))
        assertEquals(1, server.writes.get())
    }

    @Test
    fun pageAcknowledgementAdvancesNewExplicitBaselineBeforeDelete(): Unit = fixture {
        val page = record(3)
        server.loseReply = true
        assertTrue(runCatching { dispatch() }.isFailure)
        intents.record(key, false, 101, page.baseline, emptyList()) {}
        assertEquals(KomgaProgressDispatchResult.PENDING, dispatchIntent())
        assertNull(pages.attempt(key))
        assertEquals(4, KomgaProgressBaseline.decode(intents.get(key)?.baseline)?.page)
        assertEquals(1, server.writes.get())
        server.loseReply = false
        assertEquals(KomgaProgressDispatchResult.SYNCED, dispatchIntent())
        assertNull(server.page)
        assertEquals(2, server.writes.get())
        assertEquals(page.copy(supersededByRevision = intents.get(key)!!.revision), pages.get(key))
        assertTrue(!pages.hasUnsettled(key.connectionId, key.ownerKey))
        assertTrue(runCatching { dispatch() }.exceptionOrNull() is koharia.komga.progress.KomgaProgressSuperseded)
        assertEquals(KomgaProgressDispatchResult.IDLE, dispatchCurrent())
        assertEquals(2, server.writes.get())
    }

    @Test
    fun explicitRequestRecoveryCannotAcknowledgeANewerUnread(): Unit = fixture {
        val first = mark(true)
        server.loseReply = true
        assertTrue(runCatching { dispatchIntent() }.isFailure)
        val newer = intents.record(key, false, 101, first.baseline, emptyList()) {}
        assertEquals(KomgaProgressDispatchResult.PENDING, dispatchIntent())
        assertEquals(newer.revision, intents.get(key)?.revision)
        assertEquals(KomgaReadStatusPhase.PENDING, intents.get(key)?.phase)
        assertEquals(1, server.writes.get())
        server.loseReply = false
        assertEquals(KomgaProgressDispatchResult.SYNCED, dispatchIntent())
        assertEquals(2, server.writes.get())
    }

    @Test
    fun explicitConflictRetainsAmbiguousRequestAndRejectsReplacementPublication(): Unit = fixture {
        mark(true)
        server.loseReply = true
        assertTrue(runCatching { dispatchIntent() }.isFailure)
        server.hash = "replaced"
        assertEquals(KomgaProgressDispatchResult.CONFLICT, dispatchIntent())
        assertNotNull(intents.attempt(key))
        assertEquals(KomgaReadStatusPhase.CONFLICT, intents.get(key)?.phase)
        assertEquals(1, server.writes.get())
    }

    @Test
    fun explicitPermissionDenialNeedsNewUserIntentAndUnknownEvidenceStaysHeld(): Unit = fixture {
        mark(true)
        server.patchStatus = 403
        assertEquals(KomgaProgressDispatchResult.DENIED, dispatchIntent())
        assertNull(intents.attempt(key))
        server.patchStatus = 200
        assertEquals(KomgaProgressDispatchResult.DENIED, dispatchIntent())
        mark(true)
        assertEquals(KomgaProgressDispatchResult.SYNCED, dispatchIntent())
        intents.record(key, false, 103, null, emptyList()) {}
        assertEquals(KomgaProgressDispatchResult.HELD, dispatchIntent())
        assertEquals(2, server.writes.get())
    }

    @Test
    fun explicitOwnerReplacementAfterSendRetainsOldAccountEvidence(): Unit = fixture {
        mark(true)
        server.onPatch = {
            check(sourcePreferences("source_${source.id}").edit().putString("Password", "replacement").commit())
        }
        assertTrue(runCatching { dispatchIntent() }.isFailure)
        assertNotNull(intents.attempt(key))
        assertTrue(runCatching { dispatchIntent() }.isFailure)
        assertEquals(1, server.writes.get())
    }

    @Test
    fun cleanupInterruptedFixture(): Unit = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.containsKey("cleanupPageFixtureId"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val id = checkNotNull(args.getString("cleanupPageFixtureId")).toLong()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val prefs = sourcePreferences("source_$id")
        check(prefs.getString("Address", "")!!.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+")))
        check(prefs.getString("Username", "") == "fixture" && prefs.getString("Password", "") == "fixture")
        val source = KomgaSource(id)
        val key = koharia.domain.komga.KomgaReadStatusKey(id, source.progressAccount().key, "book")
        val pages = Injekt.get<KomgaPageEventRepository>()
        val event = checkNotNull(pages.get(key))
        val baseline = checkNotNull(KomgaProgressBaseline.decode(event.baseline))
        check(event.generation == 1L && event.page == 3L && event.total == 10L && event.readAt == 100L)
        check(baseline.bookId == "book" && baseline.fileHash == "publication" && baseline.size == 100L)
        check(pages.attempt(key)?.event == event)
        check(Injekt.get<KomgaReadStatusRepository>().forOwner(id, key.ownerKey).isEmpty())
        pages.removeConnection(id)
        context.deleteSharedPreferences("source_$id")
    }

    @Test
    fun successfulPatchUsesDurableGenerationAndAuthoritativeReadback(): Unit = fixture {
        record(3)
        assertEquals(KomgaProgressDispatchResult.SYNCED, dispatch())
        assertEquals(1, server.writes.get())
        assertEquals(4, server.page)
        assertNull(pages.attempt(key))
        assertEquals("revision-1", KomgaProgressBaseline.decode(pages.get(key)?.baseline)?.progressModified)
        assertEquals(KomgaProgressDispatchResult.IDLE, dispatch())
        assertEquals(1, server.writes.get())
    }

    @Test
    fun lostResponseIsRecoveredByNewDispatcherWithoutSecondPatch(): Unit = fixture {
        record(3)
        server.loseReply = true
        assertTrue(runCatching { dispatch() }.isFailure)
        assertNotNull(pages.attempt(key))
        assertEquals(KomgaReadStatusPhase.PENDING, pages.get(key)?.phase)
        assertEquals(KomgaProgressDispatchResult.SYNCED, dispatch())
        assertEquals(1, server.writes.get())
        assertNull(pages.attempt(key))
    }

    @Test
    fun unresolvedRequestWithUnchangedRemoteIsNeverBlindlyRetried(): Unit = fixture {
        record(3)
        server.loseReply = true
        server.applyWrite = false
        assertTrue(runCatching { dispatch() }.isFailure)
        repeat(3) { assertEquals(KomgaProgressDispatchResult.AWAITING_CONFIRMATION, dispatch()) }
        assertEquals(1, server.writes.get())
        assertNotNull(pages.attempt(key))
    }

    @Test
    fun externalModificationBeforeSendIsConflictEvenWithSamePageAndReadDate(): Unit = fixture {
        server.page = 2
        server.modified = "opening"
        record(3)
        server.modified = "external"
        assertEquals(KomgaProgressDispatchResult.CONFLICT, dispatch())
        assertEquals(0, server.writes.get())
        assertEquals(KomgaReadStatusPhase.CONFLICT, record(5).phase)
        assertEquals(KomgaProgressDispatchResult.CONFLICT, dispatch())
    }

    @Test
    fun ambiguousRequestRetainsConflictEvidenceAfterExternalReplacement(): Unit = fixture {
        record(3)
        server.loseReply = true
        assertTrue(runCatching { dispatch() }.isFailure)
        server.hash = "externally-replaced"
        assertEquals(KomgaProgressDispatchResult.CONFLICT, dispatch())
        assertNotNull(pages.attempt(key))
        assertEquals(1, server.writes.get())
    }

    @Test
    fun permissionDenialAndOfflineUnknownEvidenceRequireDeliberateReissue(): Unit = fixture {
        record(3)
        server.patchStatus = 403
        assertEquals(KomgaProgressDispatchResult.DENIED, dispatch())
        assertNull(pages.attempt(key))
        assertEquals(KomgaReadStatusPhase.DENIED, record(5).phase)
        server.patchStatus = 200
        assertEquals(KomgaProgressDispatchResult.DENIED, dispatch())
        assertEquals(1, server.writes.get())
        pages.removeConnection(key.connectionId)
        pages.record(key, 0, 3, 10, 100, null) {}
        assertEquals(KomgaProgressDispatchResult.HELD, dispatch())
        assertEquals(1, server.writes.get())
    }

    @Test
    fun redirectCannotReplayAnAlreadySentPatch(): Unit = fixture {
        record(3)
        server.patchStatus = 307
        assertTrue(runCatching { dispatch() }.isFailure)
        assertEquals(1, server.writes.get())
        assertNotNull(pages.attempt(key))
        assertEquals(KomgaProgressDispatchResult.AWAITING_CONFIRMATION, dispatch())
        assertEquals(1, server.writes.get())
    }

    @Test
    fun permissionDeniedDuringRecoveryPreservesTheUncertainRequest(): Unit = fixture {
        record(3)
        server.loseReply = true
        assertTrue(runCatching { dispatch() }.isFailure)
        val attempt = pages.attempt(key)
        server.getStatus = 403
        assertEquals(KomgaProgressDispatchResult.DENIED, dispatch())
        assertEquals(attempt, pages.attempt(key))
        assertEquals(KomgaReadStatusPhase.DENIED, pages.get(key)?.phase)
        server.getStatus = 200
        assertEquals(KomgaProgressDispatchResult.DENIED, dispatch())
        assertNull(pages.attempt(key))
        assertEquals(1, server.writes.get())
    }

    @Test
    fun newerReadingDuringPatchRetainsItsGenerationAndInheritsConfirmedBaseline(): Unit = fixture {
        record(3)
        server.onPatch = { runBlocking { pages.record(key, 0, 6, 10, 101, null) {} } }
        assertEquals(KomgaProgressDispatchResult.PENDING, dispatch())
        assertEquals(2L, pages.get(key)?.generation)
        assertEquals(6L, pages.get(key)?.page)
        assertEquals(KomgaReadStatusPhase.PENDING, pages.get(key)?.phase)
        server.onPatch = {}
        assertEquals(KomgaProgressDispatchResult.SYNCED, dispatch())
        assertEquals(7, server.page)
        assertEquals(2, server.writes.get())
    }

    @Test
    fun explicitRevisionDuringPatchCannotAcknowledgeOrSendOldPagesAgain(): Unit = fixture {
        record(3)
        server.onPatch = { runBlocking { intents.record(key, false, 101, null, emptyList()) {} } }
        assertTrue(runCatching { dispatch() }.exceptionOrNull() is koharia.komga.progress.KomgaProgressSuperseded)
        assertNotNull(pages.attempt(key))
        assertTrue(runCatching { dispatch() }.isFailure)
        assertEquals(1, server.writes.get())
    }

    @Test
    fun pdfReflowEmptyReviewDoesNotWriteAndActualPageUsesComparedJournal(): Unit = fixture {
        val url = "${server.root}/api/v1/books/book"
        val captured = koharia.komga.progress.captureKomgaReflowProgressSession(
            source,
            tachiyomi.domain.chapter.model.Chapter.create().copy(url = url),
            sources,
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
        )
        val adapter = captured.adapter
        val snapshot = checkNotNull(adapter.pullPageProgress(url, kotlinx.serialization.json.JsonObject(emptyMap())))
        assertNull(snapshot.pageIndex)
        assertTrue(!snapshot.blocksAutomaticSelection && !snapshot.uncertainRequest)
        assertNull(pages.get(key))
        assertEquals(0, server.writes.get())
        adapter.recordLocalPageProgress(url, 2, 10, 101)
        assertEquals(KomgaReadStatusPhase.PENDING, pages.get(key)?.phase)
        assertTrue(!adapter.isPageProgressConfirmed(url, 2, 10, 101))
        assertEquals(KomgaProgressDispatchResult.SYNCED, dispatchCurrent())
        assertTrue(adapter.isPageProgressConfirmed(url, 2, 10, 101))
        assertTrue(!adapter.isPageProgressConfirmed(url, 3, 10, 101))
        assertTrue(!adapter.isPageProgressConfirmed(url, 2, 11, 101))
        assertTrue(!adapter.isPageProgressConfirmed(url, 2, 10, 100))
        assertEquals(3, server.page)
        assertEquals(1, server.writes.get())
        val before = pages.get(key)
        assertTrue(
            runCatching {
                adapter.recordLocalPageProgress("${server.root}/api/v1/books/other", 3, 10, 102)
            }.isFailure,
        )
        assertEquals(before, pages.get(key))
        mark(false)
        assertTrue(runCatching { captured.checkCurrent() }.isFailure)
        assertTrue(runCatching { adapter.recordLocalPageProgress(url, 3, 10, 102) }.isFailure)
        assertEquals(before, pages.get(key))
    }

    @Test
    fun pdfReflowExplicitChoiceRetainsThenResolvesComparedUncertainRequest(): Unit = fixture {
        server.page = 5
        val old = record(2)
        server.applyWrite = false
        server.loseReply = true
        assertTrue(runCatching { dispatch() }.isFailure)
        val request = checkNotNull(pages.attempt(key))
        val url = "${server.root}/api/v1/books/book"
        val adapter = koharia.komga.progress.captureKomgaReflowProgressSession(
            source,
            tachiyomi.domain.chapter.model.Chapter.create().copy(url = url),
            sources,
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
        ).adapter
        val memo = kotlinx.serialization.json.JsonObject(emptyMap())
        val snapshot = checkNotNull(adapter.pullPageProgress(url, memo))
        assertTrue(snapshot.blocksAutomaticSelection && snapshot.uncertainRequest)
        assertEquals(old, pages.get(key))
        assertEquals(request, pages.attempt(key))
        assertTrue(runCatching { adapter.acceptRemotePageProgress(url, 4, 10, 0) }.isFailure)
        val review = checkNotNull(adapter.reviewPageProgress(url, memo))
        assertEquals(4, review.pageIndex)
        assertEquals(request, pages.attempt(key))
        adapter.acceptRemotePageProgress(url, 4, 10, 0)
        assertNull(pages.attempt(key))
        assertTrue(adapter.isPageProgressConfirmed(url, 4, 10, 0))
        assertTrue(!pages.settle(request, KomgaReadStatusPhase.SYNCED, pages.get(key)?.baseline) {})
        assertTrue(runCatching { adapter.acceptRemotePageProgress(url, 4, 10, 0) }.isFailure)
        assertEquals(1, server.writes.get())
    }

    @Test
    fun pdfReflowChoiceRejectsChangedRemoteLocalAndPublication(): Unit = fixture {
        server.page = 5
        val old = record(2)
        val url = "${server.root}/api/v1/books/book"
        val adapter = koharia.komga.progress.captureKomgaReflowProgressSession(
            source,
            tachiyomi.domain.chapter.model.Chapter.create().copy(url = url),
            sources,
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
        ).adapter
        val memo = kotlinx.serialization.json.JsonObject(emptyMap())
        adapter.reviewPageProgress(url, memo)
        server.page = 6
        assertTrue(runCatching { adapter.acceptRemotePageProgress(url, 4, 10, 0) }.isFailure)
        assertEquals(old, pages.get(key))
        server.page = 5
        adapter.reviewPageProgress(url, memo)
        val newer = pages.record(key, 0, 3, 10, 102, old.baseline) {}
        assertTrue(runCatching { adapter.acceptRemotePageProgress(url, 4, 10, 0) }.isFailure)
        assertEquals(newer, pages.get(key))
        val changedMemo = kotlinx.serialization.json.buildJsonObject {
            put(
                koharia.komga.download.KomgaChapterMemo.FILE_HASH,
                kotlinx.serialization.json.JsonPrimitive("other-pdf"),
            )
        }
        assertTrue(checkNotNull(adapter.reviewPageProgress(url, changedMemo)).requiresPageMappingConfirmation)
        assertTrue(runCatching { adapter.acceptRemotePageProgress(url, 4, 10, 0) }.isFailure)
        assertEquals(newer, pages.get(key))
        assertEquals(0, server.writes.get())
    }

    @Test
    fun pdfReflowCompletedWithoutPageAcceptsOnlyOriginalLastPage(): Unit = fixture {
        server.completed = true
        val url = "${server.root}/api/v1/books/book"
        val adapter = koharia.komga.progress.captureKomgaReflowProgressSession(
            source,
            tachiyomi.domain.chapter.model.Chapter.create().copy(url = url),
            sources,
            Injekt.get(),
            Injekt.get(),
            Injekt.get(),
        ).adapter
        val snapshot = checkNotNull(adapter.reviewPageProgress(url, kotlinx.serialization.json.JsonObject(emptyMap())))
        assertTrue(snapshot.completed)
        assertNull(snapshot.pageIndex)
        assertTrue(runCatching { adapter.acceptRemotePageProgress(url, 0, 10, 0) }.isFailure)
        assertNull(pages.get(key))
        adapter.acceptRemotePageProgress(url, 9, 10, 0)
        assertTrue(adapter.isPageProgressConfirmed(url, 9, 10, 0))
        assertEquals(0, server.writes.get())
    }

    @Test
    fun cleanupInterruptedUnregisteredJournalFixture(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val id =
            InstrumentationRegistry.getArguments().getString("recoverJournalFixture")?.toLong() ?: return@runBlocking
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        check(id > 0 && !Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val preferences = sourcePreferences("source_$id")
        check(preferences.getString("Username", null) == "fixture")
        check(preferences.getString("Password", null) == "fixture")
        check(preferences.getString("AuthMode", null) == "Credentials")
        val address = java.net.URI(checkNotNull(preferences.getString("Address", null)))
        check(address.scheme == "http" && address.host == "127.0.0.1" && address.port > 0)
        check(Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>().getMangaBySourceId(id).isEmpty())
        val source = KomgaSource(id)
        val key = KomgaProgressOrdering.key(id, source.progressAccount().key, "$address/api/v1/books/book")
        androidx.work.WorkManager.getInstance(context)
            .cancelUniqueWork(koharia.komga.progress.KomgaProgressWorkManager.name(key)).result.get()
        val pages = Injekt.get<KomgaPageEventRepository>()
        val intents = Injekt.get<KomgaReadStatusRepository>()
        pages.removeConnection(id)
        intents.removeConnection(id)
        check(pages.get(key) == null && pages.attempt(key) == null)
        check(intents.get(key) == null && intents.attempt(key) == null)
        check(context.deleteSharedPreferences("source_$id"))
        instrumentation.sendStatus(2, android.os.Bundle().apply { putLong("journal_fixture_recovered", id) })
    }

    private class Fixture(val source: KomgaSource, val sources: SourceManager, val server: Server) {
        val pages = Injekt.get<KomgaPageEventRepository>()
        val intents = Injekt.get<KomgaReadStatusRepository>()
        private val session = captureKomgaProgressSession(source, sources)
        val key = KomgaProgressOrdering.key(source.id, session.account.key, "${server.root}/api/v1/books/book")
        private val ordering = KomgaProgressOrdering(intents, Injekt.get())
        private val token = koharia.komga.progress.KomgaProgressRevision(key, 0)
        private val api = KomgaApi(6, source.client)
        private val transport = KomgaApiPageTransport(api, session, "${server.root}/api/v1/books/book") {
            session.guard.requireCurrent()
            ordering.checkCurrent(token)
        }
        private val explicitTransport = KomgaApiPageTransport(api, session, "${server.root}/api/v1/books/book") {
            session.guard.requireCurrent()
        }
        suspend fun mark(read: Boolean) = intents.record(
            key,
            read,
            100,
            explicitTransport.read().encode(),
            emptyList(),
            session.guard::requireCurrent,
        )
        suspend fun dispatchIntent() = koharia.komga.progress.KomgaReadStatusDispatcher(intents, pages, ordering)
            .dispatch(key, explicitTransport, session.guard::requireCurrent)
        suspend fun record(page: Long) = checkNotNull(
            pages.record(key, 0, page, 10, 100, transport.read().encode(), session.guard::requireCurrent),
        )
        suspend fun dispatch() = KomgaPageDispatcher(
            pages,
            ordering,
        ).dispatch(token, transport, session.guard::requireCurrent)
        suspend fun dispatchCurrent() = KomgaPageDispatcher(pages, ordering)
            .dispatch(ordering.capture(key), explicitTransport, session.guard::requireCurrent)
        suspend fun drain() = koharia.komga.progress.KomgaProgressCoordinator(intents, pages, ordering)
            .dispatch(key, explicitTransport, session.guard::requireCurrent)
    }

    private fun fixture(registered: Boolean = false, block: suspend Fixture.() -> Unit): Unit = runBlocking(
        Dispatchers.IO,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val id = System.nanoTime()
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            android.os.Bundle().apply { putLong("journal_fixture_owned_id", id) },
        )
        val connections = Injekt.get<ConnectionPreferences>()
        val previousActive = connections.activeConnectionId.get()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val preferences = sourcePreferences("source_$id")
        check(preferences.all.isEmpty())
        var workName: String? = null
        try {
            Server().use { server ->
                check(
                    preferences.edit().putString("Address", server.root).putString("AuthMode", "Credentials")
                        .putString("Username", "fixture").putString("Password", "fixture").commit(),
                )
                val realSources = Injekt.get<SourceManager>()
                if (registered) {
                    connections.mutateProfiles {
                        it +
                            koharia.connection.LibraryConnectionProfile(id, "komga", "Outbox fixture")
                    }
                    kotlinx.coroutines.withTimeout(5_000) {
                        while (realSources.get(id) !is KomgaSource) kotlinx.coroutines.delay(20)
                    }
                }
                val source = if (registered) realSources.get(id) as KomgaSource else KomgaSource(id)
                val sources = if (registered) {
                    realSources
                } else {
                    object : SourceManager by realSources {
                        override fun get(sourceKey: Long) = source.takeIf { sourceKey == id }
                    }
                }
                val fixture = Fixture(source, sources, server)
                workName = koharia.komga.progress.KomgaProgressWorkManager.name(fixture.key)
                fixture.block()
            }
        } finally {
            workName?.let { androidx.work.WorkManager.getInstance(context).cancelUniqueWork(it).result.get() }
            if (registered) {
                connections.mutateProfiles { it.filterNot { profile -> profile.id == id } }
                if (connections.activeConnectionId.get() == id) connections.activeConnectionId.set(previousActive)
            }
            Injekt.get<KomgaPageEventRepository>().removeConnection(id)
            Injekt.get<KomgaReadStatusRepository>().removeConnection(id)
            context.deleteSharedPreferences("source_$id")
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                android.os.Bundle().apply { putLong("journal_fixture_cleanup", id) },
            )
        }
    }

    private class Server : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val root = "http://127.0.0.1:${listener.localPort}"
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val sseReads = AtomicInteger()

        @Volatile var sseBody = ""

        @Volatile var onSse: () -> Unit = {}

        @Volatile var page: Int? = null

        @Volatile var total = 10

        @Volatile var epub = false

        @Volatile var completed = false

        @Volatile var modified = "opening"

        @Volatile var hash = "publication"

        @Volatile var loseReply = false

        @Volatile var applyWrite = true

        @Volatile var patchStatus = 200

        @Volatile var getStatus = 200

        @Volatile var onPatch: () -> Unit = {}

        @Volatile var onGet: () -> Unit = {}
        private val worker = Thread {
            while (!listener.isClosed) {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().buffered()
                        fun line(): String = buildString {
                            while (true) {
                                val byte = input.read()
                                if (byte == -1 || byte == 10) break
                                if (byte != 13) append(byte.toChar())
                            }
                        }
                        val request = line()
                        val headers = generateSequence { line().takeIf(String::isNotEmpty) }
                            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                        if (request.startsWith("GET /api/v1/sse/v1/events ")) {
                            sseReads.incrementAndGet()
                            onSse()
                            val events = sseBody.encodeToByteArray()
                            socket.getOutputStream().apply {
                                write(
                                    (
                                        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                                            "Content-Length: ${events.size}\r\nConnection: close\r\n\r\n"
                                        )
                                        .encodeToByteArray(),
                                )
                                write(events)
                                flush()
                            }
                            return@use
                        }
                        val bytes = ByteArray(headers["content-length"]?.toInt() ?: 0)
                        var offset = 0
                        while (offset < bytes.size) {
                            val count = input.read(bytes, offset, bytes.size - offset)
                            check(count > 0)
                            offset += count
                        }
                        val delete = request.startsWith("DELETE ")
                        val patch = request.startsWith("PATCH ") || delete
                        if (!patch) {
                            reads.incrementAndGet()
                            onGet()
                        }
                        if (patch) {
                            val count = writes.incrementAndGet()
                            if (applyWrite && patchStatus == 200) {
                                if (delete) {
                                    page = null
                                    completed = false
                                } else {
                                    val payload = JSONObject(bytes.decodeToString())
                                    if (payload.has("page")) page = payload.getInt("page")
                                    completed = payload.optBoolean("completed", completed)
                                }
                                modified = "revision-$count"
                            }
                            onPatch()
                            if (loseReply) return@use
                        }
                        val progress = if (page != null || completed) {
                            """, "readProgress":{"page":$page,"completed":$completed,"lastModified":"$modified"}"""
                        } else {
                            ""
                        }
                        val media = if (epub) ",\"mediaProfile\":\"EPUB\"" else ""
                        val response = (
                            if (patch) {
                                "{}"
                            } else {
                                """{"id":"book","name":"Fixture book","fileLastModified":"2026-10-10T12:00:00","metadata":{"title":"Fixture book"},"fileHash":"$hash","sizeBytes":100,"media":{"pagesCount":$total$media}$progress}"""
                            }
                            )
                            .encodeToByteArray()
                        val status = if (patch) patchStatus else getStatus
                        val location = if (status == 307) "Location: $root/api/v1/books/book/read-progress\r\n" else ""
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: ${response.size}\r\n${location}Connection: close\r\n\r\n"
                                    ).encodeToByteArray(),
                            )
                            write(response)
                            flush()
                        }
                    }
                } catch (_: java.net.SocketException) {
                    // A rejected redirect or lost response can close the socket before its body is consumed.
                } catch (error: Exception) {
                    if (!listener.isClosed) throw error
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        override fun close() {
            listener.close()
            worker.join(5_000)
            check(!worker.isAlive)
        }
    }
}

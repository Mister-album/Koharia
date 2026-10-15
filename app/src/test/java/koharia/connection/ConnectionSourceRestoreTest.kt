package koharia.connection

import android.content.Context
import eu.kanade.tachiyomi.source.AndroidSourceManager
import eu.kanade.tachiyomi.source.CatalogueSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.repository.StubSourceRepository

class ConnectionSourceRestoreTest {
    @Test
    fun `configuration detaches and closes only its owner before writing preferences`() = runTest {
        val fixture = fixture()
        fixture.preferences.setProfiles(listOf(profile, profile.copy(id = 2)))
        runCurrent()
        val original = fixture.manager.get(1) as ManagedSource
        val other = fixture.manager.get(2)
        fixture.manager.withSourceConfiguration(1) {
            assertNull(fixture.manager.get(1))
            assertSame(other, fixture.manager.get(2))
            verify(exactly = 1) { original.close() }
            fixture.setting = "complete"
            runCurrent()
            assertNull(fixture.manager.get(1))
        }
        assertNotSame(original, fixture.manager.get(1))
        assertSame(other, fixture.manager.get(2))
    }

    @Test
    fun `failed configuration closure does not leak source deferral`() = runTest {
        val fixture = fixture()
        val original = fixture.manager.get(1) as ManagedSource
        every { original.close() } throws IllegalStateException("Close failed")
        assertTrue(runCatching { fixture.manager.withSourceConfiguration(1) { error("Must not publish") } }.isFailure)
        assertNotSame(original, fixture.manager.get(1))
        fixture.preferences.setProfiles(listOf(profile.copy(id = 2)))
        runCurrent()
        assertNull(fixture.manager.get(1))
        assertEquals(2L, fixture.manager.get(2)?.id)
    }

    @Test
    fun `same profile recreates source with restored settings before returning`() = runTest {
        val fixture = fixture()
        runCurrent()
        val original = fixture.manager.get(1)
        fixture.manager.withSourceUpdatesDeferred {
            ConnectionRestoreState.duringRestore {
                fixture.setting = "restored"
                assertSame(original, fixture.manager.get(1))
                assertTrue(ConnectionRestoreState.isRestoring)
            }
        }
        assertNotSame(original, fixture.manager.get(1))
        assertEquals(listOf("original", "restored"), fixture.createdSettings)
        assertTrue(fixture.registeredDuringRestore.none { it })
        verify(exactly = 1) { (original as ManagedSource).close() }
    }

    @Test
    fun `intermediate profile emissions never create sources with partial settings`() = runTest {
        val fixture = fixture()
        runCurrent()
        fixture.manager.withSourceUpdatesDeferred {
            fixture.preferences.setProfiles(listOf(profile.copy(name = "Restored")))
            runCurrent()
            fixture.setting = "partial"
            fixture.preferences.setProfiles(listOf(profile.copy(name = "Final"), profile.copy(id = 2)))
            runCurrent()
            assertEquals(listOf("original"), fixture.createdSettings)
            fixture.setting = "complete"
        }
        assertEquals(listOf("original", "complete", "complete"), fixture.createdSettings)
        assertEquals("Final", fixture.manager.get(1)?.name)
        assertEquals(setOf(1L, 2L), fixture.manager.getCatalogueSources().map { it.id }.toSet())
        runCurrent()
        assertEquals(3, fixture.createdSettings.size)
    }

    @Test
    fun `queued pre-restore emission cannot reinstate a removed source`() = runTest {
        val fixture = fixture()
        fixture.manager.withSourceUpdatesDeferred {
            fixture.preferences.setProfiles(listOf(profile.copy(id = 2)))
        }
        val restored = fixture.manager.get(2)
        runCurrent()
        assertNull(fixture.manager.get(1))
        assertSame(restored, fixture.manager.get(2))
        assertEquals(2, fixture.createdSettings.size)
    }

    @Test
    fun `nested restore rebuilds once after the outer settings are complete`() = runTest {
        val fixture = fixture()
        fixture.manager.withSourceUpdatesDeferred {
            fixture.manager.withSourceUpdatesDeferred { fixture.setting = "partial" }
            runCurrent()
            assertEquals(1, fixture.createdSettings.size)
            fixture.setting = "complete"
        }
        assertEquals(listOf("original", "complete"), fixture.createdSettings)
    }

    @Test
    fun `failure refreshes partially changed preferences and preserves the original exception`() = runTest {
        val fixture = fixture()
        val failure = IllegalStateException("fixture failure")
        val result = runCatching {
            fixture.manager.withSourceUpdatesDeferred {
                fixture.setting = "partially restored"
                throw failure
            }
        }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(listOf("original", "partially restored"), fixture.createdSettings)
        fixture.preferences.setProfiles(listOf(profile.copy(name = "After failure")))
        runCurrent()
        assertEquals("After failure", fixture.manager.get(1)?.name)
    }

    @Test
    fun `cancellation releases deferral and refreshes before job completion`() = runTest {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            fixture.manager.withSourceUpdatesDeferred {
                fixture.setting = "cancelled restore"
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        entered.await()
        job.cancel(CancellationException("fixture cancellation"))
        job.join()
        assertEquals(listOf("original", "cancelled restore"), fixture.createdSettings)
        fixture.preferences.setProfiles(listOf(profile.copy(name = "After cancellation")))
        runCurrent()
        assertEquals("After cancellation", fixture.manager.get(1)?.name)
    }

    private fun TestScope.fixture(): Fixture {
        val fixture = Fixture()
        fixture.preferences.setProfiles(listOf(profile))
        val provider = mockk<ConnectionProvider> {
            every { id } returns "fixture"
            every { createSource(any()) } answers {
                val restoredProfile = firstArg<LibraryConnectionProfile>()
                fixture.createdSettings += fixture.setting
                mockk<ManagedSource>(relaxed = true) {
                    every { id } returns restoredProfile.id
                    every { name } returns restoredProfile.name
                    every { lang } returns "all"
                    every { connectionProfile } returns restoredProfile
                    every { onRegistered() } answers {
                        fixture.registeredDuringRestore += ConnectionRestoreState.isRestoring
                    }
                }
            }
        }
        val repository = mockk<StubSourceRepository> {
            every { subscribeAll() } returns flowOf(emptyList())
            coEvery { getStubSource(any()) } returns null
            coEvery { upsertStubSource(any(), any(), any()) } returns Unit
        }
        fixture.manager = AndroidSourceManager(
            mockk<Context>(),
            repository,
            fixture.preferences,
            ConnectionRegistry(listOf(provider)),
            backgroundScope,
        )
        return fixture
    }

    private class Fixture {
        val preferences = ConnectionPreferences(MutableTestPreferenceStore(), Json)
        var setting = "original"
        val createdSettings = mutableListOf<String>()
        val registeredDuringRestore = mutableListOf<Boolean>()
        lateinit var manager: AndroidSourceManager
    }

    private abstract class ManagedSource : CatalogueSource, ConnectionSource, ConnectionManagedLifecycle, AutoCloseable

    companion object {
        private val profile = LibraryConnectionProfile(1, "fixture", "Original")
    }
}

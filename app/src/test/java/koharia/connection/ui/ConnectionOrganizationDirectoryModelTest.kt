package koharia.connection.ui

import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.model.ScreenModelStore
import koharia.connection.ConnectionOrganizationDirectory
import koharia.connection.ConnectionOrganizationEntry
import koharia.connection.ConnectionOrganizationPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID

@OptIn(InternalVoyagerApi::class)
class ConnectionOrganizationDirectoryModelTest {
    private val holders = mutableListOf<String>()
    private val entry = ConnectionOrganizationEntry(ConnectionOrganizationPage.COLLECTIONS, "c", "Collection")

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        holders.forEach(ScreenModelStore::onDisposeNavigator)
        Dispatchers.resetMain()
    }

    @Test
    fun `refresh failure retains names while a successful empty directory is authoritative`() = runBlocking {
        val directory = Directory()
        val model = model(directory)
        val initial = directory.next()
        assertFalse(initial.refresh)
        assertEquals(ConnectionOrganizationPage.entries, initial.pages)
        initial.result.complete(listOf(entry))
        await(model) { it.loaded && !it.loading }
        model.load(refresh = true)
        val refresh = directory.next()
        assertTrue(refresh.refresh)
        refresh.result.completeExceptionally(IOException("offline"))
        val retained = await(model) { !it.loading && it.error != null }
        assertEquals(listOf(entry), retained.entries)
        assertTrue(retained.loaded)
        model.load(refresh = true)
        directory.next().result.complete(emptyList())
        val empty = await(model) { !it.loading && it.error == null }
        assertTrue(empty.loaded)
        assertTrue(empty.entries.isEmpty())
    }

    @Test
    fun `late uncancellable request cannot overwrite the newest directory`() = runBlocking {
        val directory = Directory()
        val model = model(directory)
        val old = directory.next()
        model.load(refresh = true)
        directory.next().result.complete(listOf(entry))
        await(model) { it.loaded && !it.loading }
        old.result.complete(emptyList())
        withTimeout(5_000) { old.finished.await() }
        assertEquals(listOf(entry), model.state.value.entries)
    }

    @Test
    fun `obsolete account result never becomes a loaded directory`() = runBlocking {
        val directory = Directory()
        val model = model(directory)
        val request = directory.next()
        directory.active = false
        request.result.complete(listOf(entry))
        val state = await(model) { !it.loading && it.error != null }
        assertFalse(state.loaded)
        assertTrue(state.entries.isEmpty())
    }

    private fun model(directory: Directory): ConnectionOrganizationDirectoryModel {
        val holder = "directory-test-${UUID.randomUUID()}".also(holders::add)
        return ScreenModelStore.getOrPut(holder, null) {
            ConnectionOrganizationDirectoryModel(directory, ConnectionOrganizationPage.entries)
        }
    }

    private suspend fun await(
        model: ConnectionOrganizationDirectoryModel,
        condition: (ConnectionOrganizationDirectoryState) -> Boolean,
    ) = withTimeout(5_000) { model.state.first(condition) }

    private class Directory : ConnectionOrganizationDirectory {
        override val namespace = "account-a"
        override val changes = MutableSharedFlow<Unit>()

        @Volatile var active = true
        private val requests = Channel<Request>(Channel.UNLIMITED)

        override fun checkActive() = check(active)

        override suspend fun entries(
            pages: List<ConnectionOrganizationPage>,
            refresh: Boolean,
        ): List<ConnectionOrganizationEntry> = withContext(NonCancellable) {
            val request = Request(pages, refresh)
            requests.send(request)
            try {
                request.result.await()
            } finally {
                request.finished.complete(Unit)
            }
        }

        suspend fun next() = withTimeout(5_000) { requests.receive() }
    }

    private class Request(val pages: List<ConnectionOrganizationPage>, val refresh: Boolean) {
        val result = CompletableDeferred<List<ConnectionOrganizationEntry>>()
        val finished = CompletableDeferred<Unit>()
    }
}

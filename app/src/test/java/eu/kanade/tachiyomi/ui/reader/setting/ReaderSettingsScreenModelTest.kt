package eu.kanade.tachiyomi.ui.reader.setting

import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class ReaderSettingsScreenModelTest {
    @Test
    fun `closing one reader cancels its settings subscriptions without stopping another reader`() = runBlocking {
        val oldOwner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val newOwner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val consumers = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val oldState = MutableStateFlow(ReaderViewModel.State())
        val newState = MutableStateFlow(ReaderViewModel.State())
        fun model(state: MutableStateFlow<ReaderViewModel.State>, owner: CoroutineScope) = ReaderSettingsScreenModel(
            readerState = state,
            scope = owner,
            onChangeReadingMode = {},
            onChangeOrientation = {},
            preferences = ReaderPreferences(InMemoryPreferenceStore()),
        )
        try {
            for (model in listOf(model(oldState, oldOwner), model(newState, newOwner))) {
                consumers.launch { model.viewerFlow.collect {} }
                consumers.launch { model.mangaFlow.collect {} }
            }
            withTimeout(2_000) {
                while (oldState.subscriptionCount.value != 2 || newState.subscriptionCount.value != 2) yield()
            }
            oldOwner.cancel()
            withTimeout(2_000) { while (oldState.subscriptionCount.value != 0) yield() }
            assertEquals(2, newState.subscriptionCount.value)
            newOwner.cancel()
            withTimeout(2_000) { while (newState.subscriptionCount.value != 0) yield() }
            assertEquals(0, oldState.subscriptionCount.value)
        } finally {
            oldOwner.cancel()
            newOwner.cancel()
            consumers.cancel()
        }
    }
}

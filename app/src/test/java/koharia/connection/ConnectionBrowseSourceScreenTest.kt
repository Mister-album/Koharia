package koharia.connection

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.ScreenModelStore
import eu.kanade.tachiyomi.source.Source
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

@OptIn(InternalVoyagerApi::class)
class ConnectionBrowseSourceScreenTest {
    @Test fun `source replacement disposes models while same instance retains browsing state`() {
        val screen = Shelf()
        val before = mockk<Source> { every { id } returns 42 }
        val after = mockk<Source> { every { id } returns 42 }
        try {
            screen.bindSource(before)
            val generation = screen.generation
            val first = ScreenModelStore.getOrPut(screen.key, "$generation") { Model() }
            val dependency = ScreenModelStore.getOrPutDependency(first, "scope", { it: Model ->
                it.onDispose()
            }) { Model() }
            screen.bindSource(before)
            assertEquals(generation, screen.generation)
            assertSame(first, ScreenModelStore.getOrPut(screen.key, "$generation") { Model() })
            assertEquals(0, first.disposals)
            screen.bindSource(after)
            assertNotEquals(generation, screen.generation)
            assertEquals(1, first.disposals)
            assertEquals(1, dependency.disposals)
            val next = ScreenModelStore.getOrPut(screen.key, "${screen.generation}") { Model() }
            screen.bindSource(null)
            assertEquals(1, next.disposals)
            val missing = screen.generation
            screen.bindSource(null)
            assertEquals(missing, screen.generation)
        } finally {
            ScreenModelStore.onDispose(screen)
        }
    }

    @Test fun `recreated screen with retained Voyager key does not reset the same source`() {
        val screen = Shelf()
        val source = mockk<Source>()
        try {
            screen.bindSource(source)
            val old = ScreenModelStore.getOrPut(screen.key, "${screen.generation}") { Model() }
            val restored = Shelf(screen.key)
            restored.bindSource(source)
            assertEquals(screen.generation, restored.generation)
            assertSame(old, ScreenModelStore.getOrPut(restored.key, "${restored.generation}") { Model() })
            assertEquals(0, old.disposals)
        } finally {
            ScreenModelStore.onDispose(screen)
        }
    }

    private class Model : ScreenModel {
        var disposals = 0
        override fun onDispose() {
            disposals++
        }
    }

    private class Shelf(restoredKey: String? = null) : ConnectionBrowseSourceScreen() {
        override val key = restoredKey ?: super.key
        override val sourceId = 42L
        val generation get() = connectionSourceGeneration

        @Composable override fun Content() = Unit
        override suspend fun search(query: String) = Unit
        override suspend fun searchGenre(name: String) = Unit
        override suspend fun refresh() = Unit
    }
}

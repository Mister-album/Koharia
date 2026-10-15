package koharia.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.ScreenModelStore
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Retain a shelf while navigating; retire its models when its owning Source is replaced. */
@OptIn(InternalVoyagerApi::class)
abstract class ConnectionBrowseSourceScreen : Screen(), ConnectionBrowseScreen {
    protected val connectionSourceGeneration: Long get() = binding().generation

    // Keep ownership in Voyager's runtime store, never in the serialized Screen. This also
    // retains the binding across Activity recreation alongside the shelf's existing models.
    private class SourceBinding : ScreenModel {
        var source: Source? = null
        var generation = 0L
        var initialized = false
    }

    private fun binding() = ScreenModelStore.getOrPut(key, "connection-source") { SourceBinding() }

    @Composable
    protected fun observeConnectionSource(): Source? {
        val manager = remember { Injekt.get<SourceManager>() }
        val sources by remember(manager) { manager.catalogueSources }.collectAsState(manager.getCatalogueSources())
        val source = sources.firstOrNull { it.id == sourceId }
        bindSource(source)
        return source
    }

    internal fun bindSource(source: Source?) {
        val binding = binding()
        if (binding.initialized && binding.source === source) return
        if (binding.initialized) ScreenModelStore.onDispose(this)
        binding.source = source
        binding.generation++
        binding.initialized = true
        ScreenModelStore.getOrPut(key, "connection-source") { binding }
    }
}

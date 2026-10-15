package koharia.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.source.service.SourceManager

class ConnectionContentScopeController(
    private val preferences: ConnectionPreferences,
    private val sourceManager: SourceManager,
) {
    fun activeScopes(): Set<LibraryContentScope> {
        return adapter(preferences.activeConnectionId.get())?.availableContentScopes() ?: DEFAULT_SCOPES
    }

    fun activeScopesChanges(): Flow<Set<LibraryContentScope>> {
        return combine(preferences.activeConnectionId.changes(), sourceManager.catalogueSources) { id, sources ->
            sources.firstOrNull { it.id == id } as? ConnectionBrowseAdapter
        }
            .distinctUntilChanged { previous, current -> previous === current }
            .flatMapLatest { source ->
                source?.contentScopesChanges() ?: flowOf(DEFAULT_SCOPES)
            }
            .distinctUntilChanged()
    }

    private fun adapter(connectionId: Long): ConnectionBrowseAdapter? {
        return sourceManager.get(connectionId) as? ConnectionBrowseAdapter
    }

    private companion object {
        val DEFAULT_SCOPES = setOf(LibraryContentScope.ALL)
    }
}

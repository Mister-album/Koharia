package koharia.connection

import kotlinx.coroutines.flow.Flow
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
        return preferences.activeConnectionId.changes()
            .flatMapLatest { connectionId ->
                adapter(connectionId)?.contentScopesChanges() ?: flowOf(DEFAULT_SCOPES)
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

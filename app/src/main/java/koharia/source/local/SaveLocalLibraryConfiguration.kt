package koharia.source.local

import koharia.connection.ConnectionLibraryRefreshResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The initial scan belongs to setup, not to opening the cached shelf. */
internal suspend fun saveLocalLibraryConfiguration(
    preferences: LocalLibraryPreferences,
    config: LocalLibraryConfig,
    assignments: Map<String, String>,
    persist: suspend () -> Unit = { preferences.saveLibraryDraft(config.copy(setupCompleted = true), assignments) },
    scan: suspend () -> Result<ConnectionLibraryRefreshResult>,
): Result<ConnectionLibraryRefreshResult>? = withContext(NonCancellable) {
    val newlyConfigured = !preferences.getConfig().setupCompleted
    persist()
    if (newlyConfigured) runCatching { scan().getOrThrow() } else null
}

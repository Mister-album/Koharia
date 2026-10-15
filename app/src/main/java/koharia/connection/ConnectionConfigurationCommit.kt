package koharia.connection

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Draft payloads stay in private preferences; the ownership journal contains only IDs and key names. */
internal class ConnectionConfigurationCommit(
    private val journal: ConnectionIdentityChanges,
    private val live: (Long) -> SharedConfigStorage,
    private val draft: (Long) -> SharedConfigStorage,
) {
    suspend fun commit(
        id: Long,
        replaceSource: suspend (suspend () -> Unit) -> Unit,
        writeDraft: suspend () -> Unit,
    ) {
        if (journal.pendingConfigurations().any { it.connectionId == id }) {
            replaceSource { recover(id) }
            journal.finishConfiguration(id)
            clearDraft(id)
        }
        journal.beginConfiguration(id)
        try {
            replaceSource {
                val previous = live(id).values().toMap()
                check(MARKER !in previous)
                val staged = draft(id)
                check(staged.commit(staged.values().keys.associateWith { null } + previous)) {
                    "Could not initialize configuration draft"
                }
                writeDraft()
                check(staged.commit(mapOf(MARKER to true))) { "Could not persist configuration draft" }
                val updated = staged.values().filterKeys { it != MARKER }
                val changed = (previous.keys + updated.keys).filterTo(mutableSetOf()) {
                    previous[it] != updated[it]
                }
                journal.readyConfiguration(id, changed)
                recover(id)
            }
            journal.finishConfiguration(id)
            clearDraft(id)
        } catch (error: Throwable) {
            // A prepared draft may already be live. Leave it paused and replayable until source publication succeeds.
            if (journal.pendingConfigurations().any { it.connectionId == id && it.keys == null }) {
                withContext(NonCancellable) {
                    journal.finishConfiguration(id)
                    clearDraft(id)
                }
            }
            throw error
        }
    }

    /** Startup has no old sessions; callers finish the journal only after this returns successfully. */
    fun recover(id: Long) {
        val change = journal.pendingConfigurations().first { it.connectionId == id }
        val keys = change.keys ?: return
        val values = draft(id).values()
        check(values[MARKER] == true) { "Prepared configuration draft is unavailable" }
        check(live(id).commit(keys.associateWith { values[it] })) { "Could not publish connection settings" }
    }

    fun finishRecovery(id: Long) {
        journal.finishConfiguration(id)
        clearDraft(id)
    }

    private fun clearDraft(id: Long) {
        // Cleanup cannot roll back an already published configuration. A later draft replaces every key.
        runCatching { draft(id).let { it.commit(it.values().keys.associateWith { null }) } }
    }

    private companion object {
        const val MARKER = "__koharia_configuration_draft_ready"
    }
}

package koharia.suwayomi

import java.util.concurrent.ConcurrentHashMap

/**
 * In-progress migration draft. The step screens are Voyager screens that must stay serializable,
 * so they carry only the draft id and keep the selected entries here. A draft is dropped when the
 * run screen accepts it or the reader backs out of the flow.
 */
class SuwayomiMigrationDraft(val id: Long, val identity: SuwayomiIdentity, val sourceId: Long, val sourceName: String) {
    var candidates: List<SuwayomiMigrationCandidate> = emptyList()
    var targetSourceIds: List<Long> = emptyList()
    var options: SuwayomiMigrationOptions = SuwayomiMigrationOptions()
    var filters: SuwayomiMigrationRunFilters = SuwayomiMigrationRunFilters()
    var extraSearchQuery: String? = null
}

object SuwayomiMigrationDrafts {
    private val drafts = ConcurrentHashMap<Long, SuwayomiMigrationDraft>()
    private val nextId = java.util.concurrent.atomic.AtomicLong(1)

    fun create(identity: SuwayomiIdentity, sourceId: Long, sourceName: String): SuwayomiMigrationDraft =
        SuwayomiMigrationDraft(nextId.getAndIncrement(), identity, sourceId, sourceName).also { drafts[it.id] = it }

    fun get(id: Long, identity: SuwayomiIdentity): SuwayomiMigrationDraft? = drafts[id]?.takeIf {
        it.identity ==
            identity
    }

    fun release(id: Long) {
        drafts.remove(id)
    }
}

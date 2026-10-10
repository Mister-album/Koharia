package koharia.komga.api

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class KomgaOrganizationUpdate(
    val sourceId: Long,
    val kind: KomgaOrganizationKind? = null,
    val id: String? = null,
    val progressOnly: Boolean = false,
)

object KomgaOrganizationUpdates {
    private val events = MutableSharedFlow<KomgaOrganizationUpdate>(extraBufferCapacity = 64)
    val changes = events.asSharedFlow()

    fun notify(update: KomgaOrganizationUpdate) {
        events.tryEmit(update)
    }
}

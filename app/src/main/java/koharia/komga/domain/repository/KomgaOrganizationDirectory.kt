package koharia.komga.domain.repository

import koharia.connection.ConnectionOrganizationDirectory
import koharia.connection.ConnectionOrganizationEntry
import koharia.connection.ConnectionOrganizationPage
import koharia.komga.api.KomgaOrganizationKind
import koharia.komga.api.KomgaOrganizationQuery
import koharia.komga.api.KomgaOrganizationUpdates
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

class KomgaOrganizationDirectory(
    private val repository: KomgaOrganizationRepository,
) : ConnectionOrganizationDirectory {
    override val namespace = repository.namespace
    override val changes = KomgaOrganizationUpdates.changes
        .filter { it.sourceId == repository.source.id && !it.progressOnly }
        .map { }

    override fun checkActive() = repository.checkActive()

    override suspend fun entries(
        pages: List<ConnectionOrganizationPage>,
        refresh: Boolean,
    ): List<ConnectionOrganizationEntry> {
        repository.checkActive()
        val entries = pages.flatMap { page ->
            val kind = when (page) {
                ConnectionOrganizationPage.COLLECTIONS -> KomgaOrganizationKind.COLLECTION
                ConnectionOrganizationPage.READ_LISTS -> KomgaOrganizationKind.READ_LIST
            }
            repository.api.list(kind, KomgaOrganizationQuery(unpaged = true), refresh).content.map {
                ConnectionOrganizationEntry(page, it.id, it.name)
            }
        }
        repository.checkActive()
        return entries
    }
}

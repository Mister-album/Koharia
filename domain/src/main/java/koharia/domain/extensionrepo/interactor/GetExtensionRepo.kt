package koharia.domain.extensionrepo.interactor

import kotlinx.coroutines.flow.Flow
import koharia.domain.extensionrepo.model.ExtensionRepo
import koharia.domain.extensionrepo.repository.ExtensionRepoRepository

class GetExtensionRepo(
    private val repository: ExtensionRepoRepository,
) {
    fun subscribeAll(): Flow<List<ExtensionRepo>> = repository.subscribeAll()

    suspend fun getAll(): List<ExtensionRepo> = repository.getAll()
}

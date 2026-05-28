package koharia.domain.extensionrepo.interactor

import koharia.domain.extensionrepo.repository.ExtensionRepoRepository

class DeleteExtensionRepo(
    private val repository: ExtensionRepoRepository,
) {
    suspend fun await(baseUrl: String) {
        repository.deleteRepo(baseUrl)
    }
}

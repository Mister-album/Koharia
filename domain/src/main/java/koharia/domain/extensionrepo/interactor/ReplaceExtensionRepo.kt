package koharia.domain.extensionrepo.interactor

import koharia.domain.extensionrepo.model.ExtensionRepo
import koharia.domain.extensionrepo.repository.ExtensionRepoRepository

class ReplaceExtensionRepo(
    private val repository: ExtensionRepoRepository,
) {
    suspend fun await(repo: ExtensionRepo) {
        repository.replaceRepo(repo)
    }
}

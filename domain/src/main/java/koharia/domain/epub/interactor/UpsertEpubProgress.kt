package koharia.domain.epub.interactor

import koharia.domain.epub.model.EpubProgress
import koharia.domain.epub.repository.EpubProgressRepository

class UpsertEpubProgress(
    private val repository: EpubProgressRepository,
) {

    suspend fun await(progress: EpubProgress) {
        repository.upsertProgress(progress)
    }

    suspend fun awaitIfUnchanged(
        expected: EpubProgress?,
        progress: EpubProgress,
        checkCurrent: suspend () -> Unit,
    ): Boolean = repository.compareAndSetProgress(expected, progress, checkCurrent)
}

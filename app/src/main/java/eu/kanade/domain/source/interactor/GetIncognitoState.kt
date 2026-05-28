package eu.kanade.domain.source.interactor

import eu.kanade.domain.base.BasePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

class GetIncognitoState(
    private val basePreferences: BasePreferences,
) {
    fun await(sourceId: Long?): Boolean {
        return basePreferences.incognitoMode.get()
    }

    fun subscribe(sourceId: Long?): Flow<Boolean> {
        return basePreferences.incognitoMode.changes().distinctUntilChanged()
    }
}

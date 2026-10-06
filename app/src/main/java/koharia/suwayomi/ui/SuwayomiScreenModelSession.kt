package koharia.suwayomi.ui

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import koharia.source.suwayomi.SuwayomiSource
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job

/** Stop suspended collectors and in-flight requests when settings replace their owning session. */
internal fun ScreenModel.bindSession(session: SuwayomiSource.Session) {
    val modelScope = screenModelScope
    val handle = session.scope.coroutineContext.job.invokeOnCompletion { modelScope.cancel() }
    modelScope.coroutineContext.job.invokeOnCompletion { handle.dispose() }
}

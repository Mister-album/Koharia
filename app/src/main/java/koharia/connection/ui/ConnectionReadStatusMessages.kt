package koharia.connection.ui

import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.chapter.interactor.SetReadStatus
import koharia.connection.ConnectionReadStatus
import tachiyomi.i18n.MR

internal fun Iterable<SetReadStatus.Result>.readStatusMessages(): List<StringResource> = flatMap { result ->
    when (result) {
        is SetReadStatus.Result.Completed -> result.chapters.values.mapNotNull { it.messageResource() }
        is SetReadStatus.Result.InternalError -> listOf(MR.strings.connection_read_status_local_failed)
        SetReadStatus.Result.NoChapters -> emptyList()
    }
}.distinct()

internal fun ConnectionReadStatus.messageResource(): StringResource? = when (this) {
    ConnectionReadStatus.SYNCED -> null
    ConnectionReadStatus.LOCAL_ONLY -> MR.strings.connection_read_status_local
    ConnectionReadStatus.PENDING -> MR.strings.connection_read_status_pending
    ConnectionReadStatus.HELD -> MR.strings.connection_read_status_held
    ConnectionReadStatus.CONFLICT -> MR.strings.connection_read_status_conflict
    ConnectionReadStatus.DENIED -> MR.strings.connection_read_status_denied
    ConnectionReadStatus.UNAVAILABLE -> MR.strings.connection_read_status_unavailable
}

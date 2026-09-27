package koharia.kavita.ui

import android.content.Context
import koharia.kavita.KavitaException
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

fun Context.kavitaError(error: Throwable): String {
    val failure = generateSequence(error) { it.cause }.take(8).filterIsInstance<KavitaException>().firstOrNull()
    return stringResource(
        when (failure?.reason) {
            KavitaException.Reason.AUTHENTICATION -> MR.strings.kavita_error_auth
            KavitaException.Reason.ACCOUNT_CHANGED -> MR.strings.kavita_error_account
            KavitaException.Reason.PERMISSION -> MR.strings.kavita_error_permission
            KavitaException.Reason.VERSION, KavitaException.Reason.UNSUPPORTED -> MR.strings.kavita_error_version
            KavitaException.Reason.PROTOCOL -> MR.strings.kavita_error_protocol
            KavitaException.Reason.CONFLICT -> MR.strings.kavita_error_conflict
            KavitaException.Reason.FILTER -> MR.strings.kavita_error_filter
            else -> MR.strings.kavita_error_connection
        },
    )
}

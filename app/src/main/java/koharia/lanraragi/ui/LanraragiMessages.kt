package koharia.lanraragi.ui

import android.content.Context
import koharia.lanraragi.LanraragiException
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

fun Context.lanraragiError(error: Throwable): String = stringResource(
    when ((error as? LanraragiException)?.reason) {
        LanraragiException.Reason.ADDRESS -> MR.strings.lanraragi_error_address
        LanraragiException.Reason.AUTH -> MR.strings.lanraragi_error_auth
        LanraragiException.Reason.VERSION -> MR.strings.lanraragi_error_version
        LanraragiException.Reason.PROGRESS_DISABLED -> MR.strings.lanraragi_error_progress
        LanraragiException.Reason.EMPTY, LanraragiException.Reason.UNAVAILABLE -> MR.strings.lanraragi_error_unavailable
        else -> MR.strings.lanraragi_error_network
    },
)

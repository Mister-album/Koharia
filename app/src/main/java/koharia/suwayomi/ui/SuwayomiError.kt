package koharia.suwayomi.ui

import android.content.Context
import dev.icerock.moko.resources.StringResource
import koharia.suwayomi.SuwayomiException
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.IOException

fun Context.suwayomiError(error: Throwable): String = stringResource(suwayomiErrorResource(error))

internal fun suwayomiErrorResource(error: Throwable): StringResource {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    return when (causes.filterIsInstance<SuwayomiException>().firstOrNull()?.reason) {
        SuwayomiException.Reason.ADDRESS -> MR.strings.suwayomi_error_connection
        SuwayomiException.Reason.AUTH -> MR.strings.suwayomi_error_auth
        SuwayomiException.Reason.VERSION -> MR.strings.suwayomi_error_version
        SuwayomiException.Reason.PROTOCOL -> MR.strings.suwayomi_error_protocol
        SuwayomiException.Reason.NOT_FOUND -> MR.strings.suwayomi_error_missing
        SuwayomiException.Reason.SOURCE -> MR.strings.suwayomi_error_source
        SuwayomiException.Reason.PAGES -> MR.strings.suwayomi_error_pages
        SuwayomiException.Reason.SERVER -> MR.strings.suwayomi_error_server
        SuwayomiException.Reason.IMAGE -> MR.strings.suwayomi_error_image
        null -> if (causes.any {
                it is IOException
            }
        ) {
            MR.strings.suwayomi_error_connection
        } else {
            MR.strings.suwayomi_error_request
        }
    }
}

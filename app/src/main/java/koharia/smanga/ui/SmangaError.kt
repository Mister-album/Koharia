package koharia.smanga.ui

import android.content.Context
import dev.icerock.moko.resources.StringResource
import koharia.smanga.SmangaException
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

fun Context.smangaError(error: Throwable): String = stringResource(smangaErrorResource(error))

internal fun smangaErrorResource(error: Throwable): StringResource {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    return when (causes.filterIsInstance<SmangaException>().firstOrNull()?.reason) {
        SmangaException.Reason.ADDRESS -> MR.strings.smanga_error_connection
        SmangaException.Reason.AUTH -> MR.strings.smanga_error_auth
        SmangaException.Reason.PERMISSION -> MR.strings.smanga_error_permission
        SmangaException.Reason.OPDS_DISABLED -> MR.strings.smanga_error_opds
        SmangaException.Reason.NOT_FOUND -> MR.strings.smanga_error_missing
        SmangaException.Reason.PREPARING -> MR.strings.smanga_error_preparing
        SmangaException.Reason.PROTOCOL, SmangaException.Reason.INCOMPLETE -> MR.strings.smanga_error_protocol
        SmangaException.Reason.SERVER -> MR.strings.smanga_error_server
        else -> when {
            causes.any { it is SocketTimeoutException } -> MR.strings.smanga_error_timeout
            causes.any {
                it is ConnectException || it is UnknownHostException || it is SocketException ||
                    it is SSLException
            } ->
                MR.strings.smanga_error_connection
            else -> MR.strings.smanga_error_request
        }
    }
}

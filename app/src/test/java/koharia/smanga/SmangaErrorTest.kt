package koharia.smanga

import koharia.smanga.ui.SmangaShelfException
import koharia.smanga.ui.smangaErrorResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SmangaErrorTest {
    @Test
    fun `shelf wrappers preserve actionable protocol errors`() {
        for ((reason, resource) in listOf(
            SmangaException.Reason.AUTH to MR.strings.smanga_error_auth,
            SmangaException.Reason.PERMISSION to MR.strings.smanga_error_permission,
            SmangaException.Reason.SERVER to MR.strings.smanga_error_server,
            SmangaException.Reason.PROTOCOL to MR.strings.smanga_error_protocol,
        )) {
            assertEquals(resource, smangaErrorResource(SmangaShelfException(SmangaException(reason))))
        }
    }

    @Test
    fun `transport failures distinguish timeout from connection failure`() {
        assertEquals(
            MR.strings.smanga_error_timeout,
            smangaErrorResource(SmangaShelfException(SocketTimeoutException())),
        )
        assertEquals(
            MR.strings.smanga_error_connection,
            smangaErrorResource(SmangaShelfException(UnknownHostException())),
        )
    }

    @Test
    fun `unknown errors never display implementation details or raw server messages`() {
        assertEquals(
            MR.strings.smanga_error_request,
            smangaErrorResource(SmangaShelfException(IOException("sensitive server response"))),
        )
    }

    @Test
    fun `cyclic cause chains are bounded`() {
        val first = IOException()
        val second = IOException(first)
        first.initCause(second)
        assertEquals(MR.strings.smanga_error_request, smangaErrorResource(SmangaShelfException(first)))
    }
}

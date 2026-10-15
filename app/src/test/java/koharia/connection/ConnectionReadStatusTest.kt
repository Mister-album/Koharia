package koharia.connection

import eu.kanade.tachiyomi.network.HttpException
import koharia.kavita.KavitaException
import koharia.smanga.SmangaException
import koharia.suwayomi.SuwayomiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class ConnectionReadStatusTest {
    @Test
    fun `authentication and permission failures retain provider classifications`() {
        listOf(
            HttpException(401),
            HttpException(403),
            KavitaException(KavitaException.Reason.PERMISSION),
            SmangaException(SmangaException.Reason.PERMISSION),
            SuwayomiException(SuwayomiException.Reason.AUTH, 403),
        ).forEach { assertEquals(ConnectionReadStatus.DENIED, IOException("wrapper", it).readStatusFailure()) }
        assertEquals(ConnectionReadStatus.UNAVAILABLE, HttpException(500).readStatusFailure())
        assertEquals(ConnectionReadStatus.UNAVAILABLE, IOException("offline").readStatusFailure())
        assertEquals(
            ConnectionReadStatus.CONFLICT,
            ConnectionReadStatusException(ConnectionReadStatus.CONFLICT).readStatusFailure(),
        )
    }
}

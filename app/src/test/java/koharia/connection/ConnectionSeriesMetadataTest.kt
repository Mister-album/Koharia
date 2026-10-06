package koharia.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConnectionSeriesMetadataTest {
    @Test
    fun `common series metadata retains available and planned book counts separately`() {
        val memo = Json.parseToJsonElement(
            """{"publisher":" Publisher ","language":"zh-CN","ageRating":0,"booksCount":4,"totalBookCount":8}""",
        ).jsonObject
        assertEquals(ConnectionSeriesMetadata("Publisher", "zh-CN", 0, 4, 8), ConnectionSeriesMetadata.fromMemo(memo))
    }

    @Test
    fun `missing malformed and provider specific fields do not break standard details`() {
        listOf(
            """{}""",
            """{"publisher":{},"language":[],"ageRating":{},"booksCount":[],"totalBookCount":null}""",
            """{"publisher":false,"language":123,"ageRating":-1,"booksCount":-2,"totalBookCount":0}""",
            """{"publisher":" ","language":null,"ageRating":"unknown","booksCount":1.5,"custom":{"id":1}}""",
        ).forEach { payload ->
            assertEquals(
                ConnectionSeriesMetadata(),
                ConnectionSeriesMetadata.fromMemo(Json.parseToJsonElement(payload).jsonObject),
            )
        }
    }
}

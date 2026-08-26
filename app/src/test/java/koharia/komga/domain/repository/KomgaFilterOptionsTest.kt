package koharia.komga.domain.repository

import koharia.komga.api.KomgaApiClient
import koharia.komga.api.KomgaSearchCapabilities
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KomgaFilterOptionsTest {

    @Test
    fun `missing client settings endpoint does not hide libraries`() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                when (request.url.encodedPath) {
                    "/api/v1/client-settings/user/list" -> response(request, 404, "{}")
                    "/api/v1/libraries" -> response(
                        request,
                        200,
                        """[{"id":"library-1","name":"Library"}]""",
                    )
                    "/api/v1/collections" -> response(request, 200, """{"content":[]}""")
                    "/api/v1/genres",
                    "/api/v1/tags",
                    "/api/v1/publishers",
                    "/api/v1/authors",
                    -> response(request, 200, "[]")
                    else -> response(request, 404, "{}")
                }
            }
            .build()
        val apiClient = KomgaApiClient(
            baseUrl = "https://komga.test",
            headers = Headers.Builder().build(),
            client = client,
            json = Json,
            searchCapabilities = KomgaSearchCapabilities(),
        )

        val options = KomgaRepository("https://komga.test", apiClient)
            .fetchFilterOptions(forceRefresh = true)

        assertEquals(listOf("library-1"), options.libraries.map { it.id })
    }

    private fun response(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(code.toString())
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
}

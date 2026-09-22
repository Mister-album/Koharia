package koharia.epub.service

import eu.kanade.tachiyomi.network.await
import koharia.connection.ConnectionAddressRouter
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpResponse
import org.readium.r2.shared.util.http.HttpStatus
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.http.HttpTry
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Reuses the connection transport while the outer Readium cache keeps canonical resource URLs. */
internal class KomgaReadiumTransport(private val source: () -> Connection?) : HttpClient {
    data class Connection(val baseUrl: String, val client: okhttp3.OkHttpClient, val headers: okhttp3.Headers)
    private val externalClient = DefaultHttpClient()

    override suspend fun stream(request: HttpRequest): HttpTry<HttpStreamResponse> {
        val connection = source() ?: return externalClient.stream(request)
        val base = ConnectionAddressRouter.normalize(connection.baseUrl) ?: return externalClient.stream(request)
        val url = request.url.toString().toHttpUrlOrNull() ?: return externalClient.stream(request)
        if (!ConnectionAddressRouter.owns(base, url)) return externalClient.stream(request)
        return try {
            val body = when (val value = request.body) {
                is HttpRequest.Body.Bytes -> value.bytes.toRequestBody()
                is HttpRequest.Body.File -> value.file.asRequestBody()
                null -> if (request.method.name in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
            }
            val builder = Request.Builder().url(url).method(request.method.name, body)
            request.headers.forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
            connection.headers.forEach { (name, value) -> builder.header(name, value) }
            val client = connection.client.newBuilder().apply {
                request.connectTimeout?.let { connectTimeout(it.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
                request.readTimeout?.let { readTimeout(it.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
            }.build()
            val response = client.newCall(builder.build()).await()
            val mediaType = response.body.contentType()?.toString()?.let { MediaType(it) }
            if (response.code >= 400) {
                response.use {
                    Try.failure(HttpError.ErrorResponse(HttpStatus(it.code), mediaType, it.body.bytes()))
                }
            } else {
                Try.success(
                    HttpStreamResponse(
                        response = HttpResponse(
                            request = request,
                            url = request.url,
                            statusCode = HttpStatus(response.code),
                            headers = response.headers.toMultimap(),
                            mediaType = mediaType,
                        ),
                        body = response.body.byteStream(),
                    ),
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Try.failure(HttpError.IO(error as? IOException ?: IOException(error)))
        }
    }
}

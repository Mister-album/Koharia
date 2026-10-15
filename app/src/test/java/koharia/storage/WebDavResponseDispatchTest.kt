package koharia.storage

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class WebDavResponseDispatchTest {
    @Test
    fun `insufficient remote storage is a space failure and is not retried`() = runBlocking {
        var requests = 0
        var closed = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val source = object : ForwardingSource(Buffer()) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }.buffer()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(507).message("Insufficient Storage")
                .body(object : ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = 0L
                    override fun source() = source
                }).build()
        }.build()
        val backend = WebDavStorageBackend(client, "http://fixture.invalid/", "", "")
        val failure = runCatching { backend.write("book", byteArrayOf(1).inputStream(), 1) }.exceptionOrNull()
        assertEquals(StorageFailure.Reason.SPACE, (failure as StorageFailure).reason)
        assertEquals(1, requests)
        assertEquals(true, closed)
    }

    @Test
    fun `mutation and endpoint responses close away from the caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        val closed = CopyOnWriteArrayList<String>()
        var denied = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val method = chain.request().method
            val payload = if (method == "PROPFIND") {
                """
                <d:multistatus xmlns:d="DAV:"><d:response><d:href>/book</d:href><d:propstat><d:prop>
                <d:resourcetype/><d:getcontentlength>1</d:getcontentlength><d:getetag>"v1"</d:getetag>
                </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
                """.trimIndent()
            } else {
                "Operation response body"
            }
            val source = object : ForwardingSource(Buffer().writeUtf8(payload)) {
                override fun close() {
                    assertNotEquals(caller, Thread.currentThread(), "Closing $method must not block the caller")
                    closed += method
                    super.close()
                }
            }.buffer()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(
                    if (denied) {
                        403
                    } else if (method == "PROPFIND") {
                        207
                    } else {
                        201
                    },
                ).message("Fixture")
                .body(
                    object : ResponseBody() {
                        override fun contentType() = "application/xml".toMediaType()
                        override fun contentLength() = payload.toByteArray().size.toLong()
                        override fun source() = source
                    },
                ).build()
        }.build()
        val backend = WebDavStorageBackend(client, "http://fixture.invalid/", "", "")
        val entry = StorageEntry("book", false, 1, version = "\"v1\"")
        backend.checkEndpointReachable()
        backend.createDirectory("folder")
        backend.write("book", byteArrayOf(1).inputStream(), 1)
        backend.move(entry, "moved")
        backend.delete(entry)
        denied = true
        val failure = runCatching { backend.write("book", byteArrayOf(1).inputStream(), 1) }.exceptionOrNull()
        assertEquals(StorageFailure.Reason.PERMISSION, (failure as StorageFailure).reason)
        assertEquals(listOf("PROPFIND", "MKCOL", "PUT", "PROPFIND", "MOVE", "PROPFIND", "DELETE", "PUT"), closed)
    }
}

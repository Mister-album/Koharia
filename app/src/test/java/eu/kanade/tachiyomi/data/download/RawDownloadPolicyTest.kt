package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.download.model.Download
import koharia.connection.ConnectionRawDownloadAdapter
import koharia.connection.ConnectionRawDownloadResumePolicy
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.io.ByteArrayOutputStream
import java.io.IOException

class RawDownloadPolicyTest {
    @Test
    fun `mixed chapters select their own default and retain explicit modes`() {
        val source = object : TestRawAdapter() {
            override fun preferRawDownload(chapter: Chapter) = chapter.url.endsWith(".pdf")
        }
        val comic = Chapter.create().copy(url = "comic")
        val pdf = comic.copy(url = "chapter.pdf")

        assertEquals(Download.Mode.PAGE_CACHE, resolveChapterDownloadMode(source, comic, null))
        assertEquals(Download.Mode.RAW_FILE, resolveChapterDownloadMode(source, pdf, null))
        assertEquals(Download.Mode.PAGE_CACHE, resolveChapterDownloadMode(source, pdf, Download.Mode.PAGE_CACHE))
        assertEquals(Download.Mode.RAW_FILE, resolveChapterDownloadMode(source, comic, Download.Mode.RAW_FILE))
        assertEquals(Download.Mode.PAGE_CACHE, resolveChapterDownloadMode(null, comic, null))
    }

    @Test
    fun `existing raw providers keep resumable whole file defaults`() {
        val source = TestRawAdapter()

        assertEquals(ConnectionRawDownloadResumePolicy.RESUME, source.resumePolicy)
        assertEquals(Download.Mode.RAW_FILE, resolveChapterDownloadMode(source, Chapter.create(), null))
    }

    @Test
    fun `restart copies the whole response even when an old byte count is supplied`() = runBlocking {
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val copied = response("new-complete-pdf").use {
            copyRawDownloadResponse(it, output, ConnectionRawDownloadResumePolicy.RESTART, 7, progress::add)
        }

        assertEquals("new-complete-pdf", output.toString(Charsets.UTF_8.name()))
        assertEquals(16L, copied)
        assertEquals(16L, progress.last())
    }

    @Test
    fun `legacy resume keeps the downloaded prefix when a server returns 200`() = runBlocking {
        val output = ByteArrayOutputStream().apply { write("prefix".encodeToByteArray()) }
        val copied = response("prefix-and-tail").use {
            copyRawDownloadResponse(it, output, ConnectionRawDownloadResumePolicy.RESUME, 6) {}
        }

        assertEquals("prefix-and-tail", output.toString(Charsets.UTF_8.name()))
        assertEquals(15L, copied)
    }

    @Test
    fun `legacy resume appends a 206 body after the existing prefix`() = runBlocking {
        val output = ByteArrayOutputStream().apply { write("prefix".encodeToByteArray()) }
        val copied = response("-and-tail", code = 206).use {
            copyRawDownloadResponse(it, output, ConnectionRawDownloadResumePolicy.RESUME, 6) {}
        }

        assertEquals("prefix-and-tail", output.toString(Charsets.UTF_8.name()))
        assertEquals(15L, copied)
    }

    @Test
    fun `restart rejects partial and unsatisfiable responses before writing`() {
        for (code in listOf(206, 416)) {
            val output = ByteArrayOutputStream()
            response("partial", code = code).use { response ->
                assertThrows(IOException::class.java) {
                    runBlocking {
                        copyRawDownloadResponse(response, output, ConnectionRawDownloadResumePolicy.RESTART, 7) {}
                    }
                }
            }
            assertEquals(0, output.size())
        }
    }

    @Test
    fun `restart rejects truncated and oversized response bodies`() {
        for (declaredLength in listOf(2L, 100L)) {
            response("payload", declaredLength = declaredLength).use { response ->
                assertThrows(IOException::class.java) {
                    runBlocking {
                        copyRawDownloadResponse(
                            response,
                            ByteArrayOutputStream(),
                            ConnectionRawDownloadResumePolicy.RESTART,
                            0,
                        ) {}
                    }
                }
            }
        }
    }

    private fun response(payload: String, code: Int = 200, declaredLength: Long = payload.length.toLong()): Response {
        val body = object : ResponseBody() {
            private val buffer = Buffer().writeUtf8(payload)
            override fun contentType() = null
            override fun contentLength() = declaredLength
            override fun source() = buffer
        }
        return Response.Builder()
            .request(Request.Builder().url("https://download.test/chapter").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Test response")
            .body(body)
            .build()
    }

    private open class TestRawAdapter : ConnectionRawDownloadAdapter {
        override val rawDownloadClient = OkHttpClient()
        override fun rawFileRequest(resourceUrl: String, rangeStart: Long?) =
            Request.Builder().url("https://download.test/chapter").build()
    }
}

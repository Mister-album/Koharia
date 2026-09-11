package koharia.lanraragi

import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LanraragiReaderQueueTest {
    @Test
    fun `reader loads while cover queue is saturated and close cancels pending covers`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val network = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("cover")) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("image".toResponseBody()).build()
        }.build()
        val api = LanraragiApi("http://localhost/", "", network, Json)
        api.imageClient.dispatcher.maxRequestsPerHost = 1
        try {
            val cover = api.imageClient.newCall(api.request("cover"))
            val coverResult = cover.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val page = api.readerClient.newCall(api.request("page")).start()
            assertEquals("image", page.get(2, TimeUnit.SECONDS).use { it.body.string() })
            assertFalse(coverResult.isDone)
            api.close()
            assertTrue(cover.isCanceled())
        } finally {
            release.countDown()
            api.close()
        }
    }

    private fun Call.start() = CompletableFuture<Response>().also { future ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                future.completeExceptionally(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) response.close() else future.complete(response)
            }
        })
    }
}

package koharia.tts

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 直接用 OkHttp WebSocket 调真 Microsoft 服务端的集成测试。
 *
 * **目的**:绕开 Android TtsService / TtsSettingsScreen / 阅读器 UI 这条链路,
 * 单独验证 OkHttp + 我们当前 headers + 新 endpoint 端到端能否拿到 101。
 *
 * - 算法正确性由 [EdgeEngineTest] (13 个 unit test + Python oracle) 锁死
 * - 网络可达性由 device curl 200 OK 213KB 验证过
 * - **这个测试验最后一步**:OkHttp WebSocket + 我们的 Sec-MS-GEC + 真实服务端
 *
 * 跑法:`./gradlew.bat :app:testDebugUnitTest -PkohariaNetworkTests=true
 * --tests koharia.tts.EdgeEngineIntegrationTest`
 *
 * 注:这个测试**依赖真实网络**,由 `koharia.networkTests` 系统属性门控,默认跳过
 * (CI 不跑公网,避免风控/网络抖动导致 CI 变红);只用于开发时端到端 sanity check。
 */
@EnabledIfSystemProperty(named = "koharia.networkTests", matches = "true")
class EdgeEngineIntegrationTest {

    @Test
    fun `OkHttp WebSocket upgrade succeeds against real Microsoft Edge TTS server`() {
        val token = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        val nowUnixSecs = System.currentTimeMillis() / 1000
        val ticks = (nowUnixSecs + WINDOWS_EPOCH_OFFSET_SECS)
        val alignedTicks = (ticks - ticks % 300) * 10_000_000
        val strToHash = "$alignedTicks$token"
        val gec = MessageDigest.getInstance("SHA-256")
            .digest(strToHash.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02X".format(it) }

        val connectionId = UUID.randomUUID().toString()
        // Python edge-tts 7.2.8 实际 URL 拼接(communicate.py):
        //   WSS_URL + &ConnectionId=...&Sec-MS-GEC=...&Sec-MS-GEC-Version=...
        // **Sec-MS-GEC/Version 走 URL params,不是 headers**(错放 header → 403)
        // **必须带 Cookie: muid=**(drm.headers_with_muid 加,不带 → 403)
        val muid = UUID.randomUUID().toString().replace("-", "").uppercase()
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$token" +
            "&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=$gec" +
            "&Sec-MS-GEC-Version=1-143.0.3650.75"

        val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Pragma", "no-cache")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("Origin", "chrome-extension://jdiclldimpdaibmpdkjnbmckianbfold")
            .addHeader("Accept-Encoding", "gzip, deflate, br, zstd")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0",
            )
            .addHeader("Cookie", "muid=$muid;")
            .build()

        val latch = CountDownLatch(1)
        val resultHolder = ResultHolder()
        client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    resultHolder.outcome = "OPEN"
                    resultHolder.statusCode = response.code
                    resultHolder.bodyPreview = null
                    webSocket.close(1000, "test done")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    resultHolder.outcome = "FAIL"
                    resultHolder.statusCode = response?.code
                    resultHolder.bodyPreview = runCatching {
                        response?.body?.string()?.take(500)
                    }.getOrNull()
                    resultHolder.throwable = t
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    latch.countDown()
                }
            },
        )

        val completed = latch.await(30, TimeUnit.SECONDS)
        println("=".repeat(60))
        println("Edge TTS OkHttp WebSocket Test Result:")
        println("  outcome: ${resultHolder.outcome}")
        println("  status: ${resultHolder.statusCode}")
        println("  body: ${resultHolder.bodyPreview}")
        println(
            "  throwable: ${resultHolder.throwable?.javaClass?.simpleName}: ${resultHolder.throwable?.message?.take(
                200,
            )}",
        )
        println("  completed in time: $completed")
        println("  sec-ms-gec sent: $gec")
        println("=".repeat(60))

        assertTrue(completed, "WebSocket did not complete within 30 seconds")
        assertTrue(
            resultHolder.outcome == "OPEN",
            "Expected OPEN but got ${resultHolder.outcome} (status=${resultHolder.statusCode}, body=${resultHolder.bodyPreview})",
        )
    }

    private class ResultHolder {
        @Volatile var outcome: String = "PENDING"

        @Volatile var statusCode: Int? = null

        @Volatile var bodyPreview: String? = null

        @Volatile var throwable: Throwable? = null
    }

    private companion object {
        // Mirror EdgeEngine 的常量,跟 drm.py:WIN_EPOCH = 11644473600 对齐
        const val WINDOWS_EPOCH_OFFSET_SECS = 11644473600L
    }
}

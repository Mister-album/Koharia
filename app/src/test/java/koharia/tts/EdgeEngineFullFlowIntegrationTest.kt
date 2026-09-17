package koharia.tts

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 完整端到端集成测试:打开 WebSocket + 发 speech.config + 发 SSML + 等音频帧
 *
 * v0.4.2-57+:单元测试只验证 Sec-MS-GEC 算法。但之前的 101 通过不代表 full flow
 * 通 —— server 在 101 后立即 RST 的真实原因可能在 speech.config/SSML 帧内容、
 * 协议细节,或 Android Conscrypt vs JVM TLS 差异。
 *
 * 这个测试**模拟 EdgeEngine 完整交互**:
 * 1. URL + headers + Cookie:muid 跟 Python edge-tts 7.2.8 一致
 * 2. 101 后立即发 speech.config(用跟 Kotlin buildConfigFrame 完全相同的内容)
 * 3. 然后发 SSML
 * 4. 等最多 30s 接收 audio 帧,统计大小
 *
 * 真实环境跑:`./gradlew.bat :app:testDebugUnitTest -PkohariaNetworkTests=true
 * --tests koharia.tts.EdgeEngineFullFlowIntegrationTest`
 * 不依赖 emulator/device —— 直接 JVM + OkHttp + 公网。
 *
 * 由 `koharia.networkTests` 系统属性门控,默认跳过(CI 不跑公网)。
 */
@EnabledIfSystemProperty(named = "koharia.networkTests", matches = "true")
class EdgeEngineFullFlowIntegrationTest {
    @Test
    fun edge_tts_full_flow() {
        val token = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        val secMsGec = computeSecMsGec(System.currentTimeMillis() / 1000L)
        val connectionId = UUID.randomUUID().toString()
        val muid = UUID.randomUUID().toString().replace("-", "").uppercase()

        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$token" +
            "&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=$secMsGec" +
            "&Sec-MS-GEC-Version=1-143.0.3650.75"

        println("URL: $url")

        val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        val latch = CountDownLatch(1)
        var upgradeResponse: Response? = null
        var socketFailure: Throwable? = null
        val audioBytes = java.util.concurrent.atomic.AtomicInteger(0)
        val textFrames = mutableListOf<String>()

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

        val ws = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    upgradeResponse = response
                    println("OPEN: status=${response.code}")
                    val ts = makeGmtTimestamp()
                    // speech.config frame
                    val config = "X-Timestamp:$ts\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":" +
                        "{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\"," +
                        "\"wordBoundaryEnabled\":\"false\"}," +
                        "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                    webSocket.send(config)
                    // ssml frame
                    val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                        "<voice name='en-US-JennyNeural'>Hello world</voice></speak>"
                    val ssmlFrame = "X-RequestId:${UUID.randomUUID()}\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${ts}Z\r\n" +
                        "Path:ssml\r\n\r\n$ssml"
                    webSocket.send(ssmlFrame)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size < 2) return
                    val headerLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                    if (bytes.size >= headerLen + 2) {
                        val audio = bytes.substring(headerLen + 2).toByteArray()
                        audioBytes.addAndGet(audio.size)
                        println("AUDIO_FRAME size=${audio.size} total=$audioBytes")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    textFrames.add(text)
                    println("TEXT_FRAME: ${text.take(200)}")
                    if (text.contains("Path:audio") && !webSocket.close(1000, "done")) {
                        latch.countDown()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socketFailure = t
                    println("FAILURE: ${t::class.simpleName} msg=${t.message}")
                    latch.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    println("CLOSED: code=$code reason=$reason")
                    latch.countDown()
                }
            },
        )

        val completed = latch.await(30, TimeUnit.SECONDS)
        ws.close(1000, "test done")

        println("=== RESULT ===")
        println("completed in time: $completed")
        println("status: ${upgradeResponse?.code}")
        println("audio bytes received: ${audioBytes.get()}")
        println("text frames: ${textFrames.size}")
        println("throwable: ${socketFailure?.let { "${it::class.simpleName}: ${it.message}" }}")

        assertTrue(completed, "应 30s 内收到响应或失败")
    }

    private fun makeGmtTimestamp(): String {
        val fmt = java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("GMT")
        }
        return fmt.format(java.util.Date()) + "+0000 (Coordinated Universal Time)"
    }

    private fun computeSecMsGec(currentUnixSecs: Long): String {
        var ticks = currentUnixSecs + 11644473600L
        ticks -= ticks % 300L
        ticks *= 10_000_000L
        val token = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        val strToHash = "$ticks$token"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(strToHash.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }
}

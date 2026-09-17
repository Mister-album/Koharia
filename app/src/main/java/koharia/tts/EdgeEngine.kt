package koharia.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.ConnectionSpec
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import tachiyomi.core.common.util.system.logcat
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Microsoft Edge TTS 引擎(免 key 免费备用)。
 *
 * 协议:WebSocket `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1`。
 * 服务端需要 `TrustedClientToken` 查询参数 —— 本常量是社区共识(从浏览器扩展逆向得出),
 * 微软若变更 token 或协议本引擎会失效,届时切回 [MimoEngine]。
 *
 * 协议要点(参见 `docs/tts/phase-4-vendor-config.md`):
 *  1. 客户端发 2 个 text 帧(带自定义 header):
 *     - speech.config:JSON 音频配置(outputFormat=audio-24khz-48kbitrate-mono-mp3)
 *     - ssml:SSML XML(voice name + prosody 包裹的文本)
 *  2. 服务端返回多个 binary 帧:头 2 字节是大端 header 长度,之后 header 文本 + audio body
 *  3. 收到 text 帧 "Path: audio" 表示 stream 结束,客户端关闭
 *
 * 本实现**非流式** — 收集完整 audio bytes 后一次性返回,方便 [SentencePrefetcher] 复用
 * 现有 MP3 字节缓存路径。
 */
class EdgeEngine(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : TtsEngine {

    override val engineId: String = "edge"
    override val displayName: String = "Microsoft Edge TTS"

    /** Edge TTS 无需配置,始终返回 true。 */
    override fun isConfigured(): Boolean = true

    override suspend fun listVoices(): List<Voice> = PRESET_VOICES

    override suspend fun synthesize(
        sentence: Sentence,
        request: SynthesisRequest,
    ): SynthesisResult = withContext(Dispatchers.IO) {
        require(sentence.text.length <= MAX_CHARS_PER_REQUEST) {
            "sentence too long (${sentence.text.length} chars > $MAX_CHARS_PER_REQUEST)"
        }

        val voice = request.voice
        require(voice in VALID_VOICE_IDS) {
            "unknown Edge voice: $voice (valid: $VALID_VOICE_IDS)"
        }

        val ssml = buildSsml(voice, sentence.text)
        val started = System.currentTimeMillis()
        val mp3Bytes = executeSynthesis(ssml)
        val elapsed = System.currentTimeMillis() - started

        // 与 MimoEngine 一致:MP3 24kHz 单声道 66kb/s,字节数估算时长
        val estimatedDurationMs = (mp3Bytes.size * 8L * 1000) / 66000

        SynthesisResult(
            audioData = mp3Bytes,
            format = AudioFormat.MP3,
            sampleRate = SAMPLE_RATE_HZ,
            channels = CHANNELS,
            durationMs = estimatedDurationMs,
            elapsedMs = elapsed,
        )
    }

    internal fun buildSsml(voice: String, text: String): String {
        // [internal] 让 EdgeEngineTest 验证 SSML 结构 + escapeXml 不依赖真机。
        // 包含 [escapeXml] 调用,所以两个函数的回归都覆盖了。
        val lang = when {
            voice.startsWith("zh") -> "zh-CN"
            voice.startsWith("en") -> "en-US"
            else -> "en-US"
        }
        return buildString {
            append("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='")
            append(lang)
            append("'><voice name='")
            append(voice)
            append("'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>")
            append(escapeXml(text))
            append("</prosody></voice></speak>")
        }
    }

    private fun escapeXml(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    private suspend fun executeSynthesis(ssml: String): ByteArray =
        suspendCancellableCoroutine { cont ->
            logcat(LogPriority.DEBUG) {
                "[EdgeEngine] executeSynthesis ssml_len=${ssml.length}"
            }
            val connectionId = UUID.randomUUID().toString()
            // v0.4.2-57+:对照 Python edge-tts 7.2.8 `communicate.py` 实际 URL:
            //   WSS_URL + "&ConnectionId=...&Sec-MS-GEC=...&Sec-MS-GEC-Version=..."
            // **Sec-MS-GEC 和 Version 走 URL query params,不是 HTTP header**(错放 header
            // 导致服务端 token 校验失败 403)。
            // **必须带 Cookie: muid=<random>**,Python edge-tts `drm.headers_with_muid` 加。
            val secMsGec = generateSecMsGec(System.currentTimeMillis() / 1000L)
            val url = "$BASE_URL" +
                "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
                "&ConnectionId=$connectionId" +
                "&Sec-MS-GEC=$secMsGec" +
                "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

            val request = Request.Builder()
                .url(url)
                .headers(
                    REQUEST_HEADERS.newBuilder()
                        .add("Cookie", "muid=${generateMuid()};")
                        .build(),
                )
                .build()

            // v0.4.2-58:连接级诊断。真机排查过一次 "101 后 RST",保留 URL 一行
            // (含 Sec-MS-GEC / ConnectionId)足以定位;不再逐 header 打印,避免刷屏。
            logcat(LogPriority.DEBUG) {
                "[EdgeEngine] upgrade ${request.url}"
            }

            val audioChunks = mutableListOf<ByteArray>()
            var finished = false
            var streamFailed = false

            fun finishOnce(block: () -> Unit) {
                if (!finished) {
                    finished = true
                    block()
                }
            }

            val ws = httpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        logcat(LogPriority.VERBOSE) { "[EdgeEngine] WebSocket opened" }
                        webSocket.send(buildConfigFrame())
                        webSocket.send(buildSsmlFrame(ssml))
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        // 协议:2 字节大端 header length | header ASCII | audio body
                        if (bytes.size < 2) return
                        val headerLen = ((bytes[0].toInt() and 0xFF) shl 8) or
                            (bytes[1].toInt() and 0xFF)
                        if (bytes.size < headerLen + 2) {
                            logcat(LogPriority.WARN) { "[EdgeEngine] audio frame too short: ${bytes.size}" }
                            return
                        }
                        val audioData = bytes.substring(headerLen + 2).toByteArray()
                        if (audioData.isNotEmpty()) {
                            audioChunks.add(audioData)
                            // v0.4.2-58:第一条 audio 帧 log 一次,确认真的收到数据
                            if (audioChunks.size == 1) {
                                logcat(LogPriority.DEBUG) {
                                    "[EdgeEngine] first audio frame: ${audioData.size} bytes"
                                }
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        // 服务端发 text 帧 `Path:turn.end` 表示流结束。
                        // v0.4.2-58 bug fix:之前写的是 `Path:audio`(从老 edge-tts 抄错),
                        // 永远不命中 → audio 帧累积但永不关 → SentencePrefetcher coroutine
                        // 挂到 readTimeout 60s 才被取消,用户感受"按了 Listen 没声音"。
                        //
                        // 验证:Python edge-tts 7.2.8 `communicate.py:174`
                        //   `elif path == b"turn.end":` + JVM 集成测试都确认这个 marker。
                        //
                        // **直接 resume,不等 close 握手** —— 收到 turn.end 时所有 audio
                        // 帧已按序到齐(OkHttp reader 单线程顺序回调),马上返回可让
                        // SentencePrefetcher 立即开始播下一句,不必等 server 回 close ack。
                        if ((text.contains("Path:turn.end") || text.contains("Path: audio")) && !finished) {
                            logcat(LogPriority.DEBUG) {
                                "[EdgeEngine] stream end: ${text.take(80)}"
                            }
                            finishOnce {
                                val merged = mergeAudioChunks(audioChunks)
                                logcat(LogPriority.INFO) {
                                    "[EdgeEngine] synthesized ${merged.size} bytes from ${audioChunks.size} frames"
                                }
                                cont.resume(merged)
                            }
                            webSocket.close(1000, "client done")
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        // v0.4.2-57+:把响应 body 也打出来,服务端拒绝时能看到具体原因
                        // (OkHttp 默认只抛 ProtocolException,body 不会自动 attach 到异常)
                        val bodyStr = runCatching { response?.body?.string()?.take(500) }.getOrNull()
                        val statusCode = response?.code
                        logcat(LogPriority.ERROR, t) {
                            "[EdgeEngine] WebSocket failure: status=$statusCode body=$bodyStr"
                        }
                        streamFailed = true
                        finishOnce {
                            cont.resumeWithException(EdgeException("WebSocket failure: ${t.message}", t))
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        logcat(LogPriority.VERBOSE) { "[EdgeEngine] closing: code=$code reason=$reason" }
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        // 正常路径下 `Path:turn.end` 已在 onMessage(text) 里 resume 过,
                        // 这里 `finishOnce` 会直接跳过。这个分支主要兜底:
                        // 服务端没发 turn.end 就主动 close(异常但可能)时,仍把已收
                        // audio 帧返回,避免 coroutine 悬挂到 readTimeout。
                        if (!streamFailed) {
                            finishOnce {
                                val merged = mergeAudioChunks(audioChunks)
                                logcat(LogPriority.INFO) {
                                    "[EdgeEngine] synthesized (onClosed fallback) ${merged.size} bytes from ${audioChunks.size} frames"
                                }
                                cont.resume(merged)
                            }
                        }
                    }
                },
            )

            cont.invokeOnCancellation {
                logcat(LogPriority.WARN) { "[EdgeEngine] coroutine cancelled, closing WS" }
                ws.cancel()
            }
        }

    /** speech.config 帧:JSON 描述音频输出格式。 */
    private fun buildConfigFrame(): String {
        val ts = gmtTimestamp()
        return "X-Timestamp:$ts\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":" +
            "{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\"," +
            "\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
    }

    /** ssml 帧:自定义 X-Timestamp + Content-Type + Path + 空行 + SSML body。 */
    private fun buildSsmlFrame(ssml: String): String {
        val ts = gmtTimestamp()
        return "X-RequestId:${UUID.randomUUID()}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            // Python edge-tts 在 SSML 帧的 X-Timestamp 末尾追加 `Z`(注:comment 里写
            // "This is not a mistake, Microsoft Edge bug" —— 服务端实现就是这样,
            // 不带 Z 直接拒 / RST)。`buildConfigFrame` 的 X-Timestamp 不带 Z,
            // 因为那帧的格式是另外一条协议。
            "X-Timestamp:${ts}Z\r\n" +
            "Path:ssml\r\n\r\n$ssml"
    }

    /** 把分帧的 audio chunk 合并成单个 MP3 字节数组。 */
    private fun mergeAudioChunks(chunks: List<ByteArray>): ByteArray {
        val total = chunks.sumOf { it.size }
        val merged = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, merged, offset, chunk.size)
            offset += chunk.size
        }
        return merged
    }

    /**
     * 生成 MUID(Microsoft User ID)cookie 值。
     *
     * Python edge-tts 7.2.8 `drm.py:DRM.generate_muid()` 用 `secrets.token_hex(16).upper()`
     * 生成 32 字符大写 hex。服务端靠这个 MUID 关联用户/限流,**不带头会 403**。
     *
     * 这里用 UUID.randomUUID() + 32 字符 hex(去 dash),行为等价。
     */
    private fun generateMuid(): String =
        UUID.randomUUID().toString().replace("-", "").uppercase()

    private fun gmtTimestamp(): String {
        // Python edge-tts 7.2.8 `communicate.py:date_to_string()`:
        //   "%a %b %d %Y %H:%M:%S GMT+0000 (Coordinated Universal Time)"
        // 实际产出 "Tue Sep 15 2026 17:54:37 GMT+0000 (Coordinated Universal Time)"
        // 我们用 SimpleDateFormat 同构字符串,再用 `Z`/`(Coordinated Universal Time)`
        // 字面拼成等价输出。
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        return fmt.format(Date()) + "+0000 (Coordinated Universal Time)"
    }

    /**
     * Microsoft Edge TTS 防滥用校验。
     *
     * 算法(逆向自 msedge-tts Python 库 v7.2.7 `drm.py:DRM.generate_sec_ms_gec`):
     *  1. 取当前 unix 时间戳(秒) = `ticks`
     *  2. **加** Windows epoch 偏移(11644473600 秒 = 1601→1970 间秒数)
     *     —— 把 unix 时间换算成 Windows 文件时间(WFT = unix + offset)
     *  3. 向下取整到 5 分钟窗口:`ticks -= ticks % 300`
     *  4. **乘 10^7 转成 100-ns intervals**(`ticks *= 10_000_000`),
     *     这是 WFT 的标准单位,服务端用 100-ns 精度校验
     *  5. 拼字符串 `"<ticks><TRUSTED_CLIENT_TOKEN>"`(无冒号)
     *  6. SHA-256 → **大写 16 进制字符串**(`hexdigest().upper()`),64 字符
     *
     * **v0.4.2-55 的 3 个错误**(host curl 用错算法对新 endpoint 也 403):
     *  - 没乘 10^7(只用了秒单位,服务端要 100-ns 精度)
     *  - 加了冒号 `":"`(`ticks:$TOKEN` → 字符串不同,SHA-256 输入变了)
     *  - 用 Base64 而不是 hex.upper(编码方式不同)
     *
     * 服务端会校验该 token 是否匹配当前 5 分钟窗口,**每次请求必须重新生成**。
     * 缺这个 header 直接 403 Forbidden(实测)。
     *
     * [currentUnixSecs] 用参数注入,便于 `EdgeEngineTest` 用 Python oracle 算的固定时间戳
     * 验证 hash 正确性 —— 不依赖真机连 Microsoft 服务端。
     *
     * 参考:https://github.com/rany2/edge-tts/blob/master/src/edge_tts/drm.py
     */
    internal fun generateSecMsGec(currentUnixSecs: Long): String {
        var ticks = currentUnixSecs + WINDOWS_EPOCH_OFFSET_SECS
        ticks -= ticks % 300L
        ticks *= 10_000_000L // 秒 → 100-ns intervals(WFT 标准单位)
        val strToHash = "$ticks$TRUSTED_CLIENT_TOKEN" // 无冒号
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(strToHash.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) } // 大写 16 进制
    }

    companion object {
        private const val BASE_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

        /**
         * TrustedClientToken 来自社区共识(浏览器扩展逆向)。
         *
         * 微软若变更,本引擎会失效。届时需更新本常量或协议。
         */
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

        /**
         * Windows 文件时间相对 unix 时间的偏移(秒)。
         *
         * 1601-01-01 → 1970-01-01 共 11644473600 秒。用于把 `System.currentTimeMillis()/1000`
         * (unix 秒) 转换为 Windows 文件时间(秒单位)。
         */
        private const val WINDOWS_EPOCH_OFFSET_SECS = 11644473600L

        /**
         * Sec-MS-GEC 协议版本号(逆向自 Edge 143.x / Chromium 143)。
         *
         * 微软升级浏览器版本时这个号会变;若 Edge 拒绝 1-xxx 旧版本,需更新。
         * v0.4.2-55 用了 `1-130.0.2849.68` 已被 v7.2.2+ 服务端忽略 → 403。
         */
        private const val SEC_MS_GEC_VERSION = "1-143.0.3650.75"

        /** Edge 24kHz 单声道固定参数(与 MiMo 一致)。 */
        private const val SAMPLE_RATE_HZ = 24000
        private const val CHANNELS = 1
        private const val MAX_CHARS_PER_REQUEST = 1500

        /** 默认音色(xiaoxiao 阳光女声)。 */
        const val DEFAULT_VOICE_ID = "zh-CN-XiaoxiaoNeural"

        private val REQUEST_HEADERS: Headers = Headers.Builder()
            .add("Pragma", "no-cache")
            .add("Cache-Control", "no-cache")
            // Origin 必须跟 edge-tts 7.2.8 一致(`jdiclldimpdaibmpdkjnbmckianbfold`)。
            // v0.4.2-57 之前我抄了更早版本的 extension id `jdiccldmpdaohmjpccoobjckcmcdgghg`,
            // 服务端校验不通过 → 403。
            .add("Origin", "chrome-extension://jdiclldimpdaibmpdkjnbmckianbfold")
            .add("Accept-Encoding", "gzip, deflate, br, zstd")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0",
            )
            // OkHttp 自动加 Sec-WebSocket-Version: 13 和 Upgrade: websocket,
            // Cookie: muid 也在 executeSynthesis 里加(每次都不一样)。
            // 注意 Python 7.2.8 (旧 endpoint) 不发 Sec-WebSocket-Protocol —
            // 那是 PR #412 (新 endpoint) 才加的,跟 7.2.8 无关。
            .build()

        /**
         * Edge TTS 预设音色列表(社区常用 6 个)。
         * 完整列表通过 `https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list?trustedclienttoken=...`
         * 获取,但需要 HTTP 调用且列表变动频繁;v1 用硬编码子集。
         */
        val PRESET_VOICES: List<Voice> = listOf(
            Voice("zh-CN-XiaoxiaoNeural", "Xiaoxiao", "zh", VoiceType.PRESET, "阳光活力女声"),
            Voice("zh-CN-YunxiNeural", "Yunxi", "zh", VoiceType.PRESET, "温暖磁性男声"),
            Voice("zh-CN-YunyangNeural", "Yunyang", "zh", VoiceType.PRESET, "专业播报男声"),
            Voice("en-US-JennyNeural", "Jenny", "en", VoiceType.PRESET, "Friendly female"),
            Voice("en-US-AriaNeural", "Aria", "en", VoiceType.PRESET, "Cheerful female"),
            Voice("en-US-GuyNeural", "Guy", "en", VoiceType.PRESET, "Authoritative male"),
        )

        /** 合法 voice id 集合(供 [synthesize] require 检查)。 */
        val VALID_VOICE_IDS: Set<String> = PRESET_VOICES.mapTo(mutableSetOf()) { it.id }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // Edge WebSocket 流可能慢(readTimeout 不能太短)
            .readTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // v0.4.2-58:对齐 OpenSSL/Python edge-tts 的 TLS ClientHello 形态。
            // Android Conscrypt 默认会协商 TLS 1.3 + ChaCha20 / X25519 等扩展,
            // 部分服务端(疑似 Bing Edge TTS gateway)对 Conscrypt 的 ClientHello
            // 直接 101 后 RST —— 而 JVM SunJSSE + Python aiohttp OpenSSL 都正常。
            // 强制限定到 RESTRICTED_TLS(TLS 1.2 + AES-GCM + ECDHE),与 OpenSSL
            // 默认相同 cipher order,基本对齐 Python 的 ClientHello 形态。
            .connectionSpecs(
                listOf(
                    ConnectionSpec.RESTRICTED_TLS,
                    ConnectionSpec.MODERN_TLS,
                    ConnectionSpec.COMPATIBLE_TLS,
                ),
            )
            .build()
    }
}

class EdgeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

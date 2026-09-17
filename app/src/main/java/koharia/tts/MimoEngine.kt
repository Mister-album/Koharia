package koharia.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * MiMo TTS 引擎实现。
 *
 * Phase 0 探路报告：参见 `docs/tts/miMo-api-behavior.md`
 *
 * 关键约束（来自实测）：
 * - 单次响应封顶 ~5MB → 单句请求足够（句长一般 < 200 字）
 * - 10 路并发无 QPS 限制
 * - OpenAI 兼容协议：user=风格指令，assistant=朗读文本
 * - 音频 MP3 24kHz 单声道 LAME3.100
 * - 流式 SSE 格式：data: {json}\n\n
 * - 音频永不过期（expires_at=null）
 *
 * Phase 1: 仅实现非流式 + 句级独立请求（最简可靠）
 * Phase 2: 改流式 + 句级独立（节省首字节延迟）
 * Phase 4: API key 改运行时读取（[apiKeyProvider] lambda），不再编译期注入
 */
class MimoEngine(
    /**
     * API key provider。
     *
     * 每次 [synthesize] 调用前重新执行,通常读 [TtsSecurePreferences.getApiKey]。
     * 这样用户改 key 后,**当前正在飞的 playback 下一句就用新 key**,无需重建引擎。
     *
     * 返回 `null` / blank 时 [isConfigured] 返回 false,synthesize 立即 fail-fast。
     */
    private val apiKeyProvider: () -> String?,

    /**
     * Base URL provider。
     *
     * 每次 synthesize 重新执行,通常读 [TtsSecurePreferences.getBaseUrl];
     * `null` 时用 [DEFAULT_BASE_URL]。
     */
    private val baseUrlProvider: () -> String = { DEFAULT_BASE_URL },

    /**
     * 模型 ID
     *
     * 可选值：
     * - mimo-v2.5-tts（预设音色）
     * - mimo-v2.5-tts-voiceclone（克隆音色）
     * - mimo-v2.5-tts-voicedesign（设计音色）
     */
    private val model: String = "mimo-v2.5-tts",

    /**
     * OkHttp 客户端（Phase 1 由调用方注入，便于测试 mock）
     */
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : TtsEngine {

    override val engineId: String = "mimo"
    override val displayName: String = "MiMo TTS"

    override fun isConfigured(): Boolean = !apiKeyProvider().isNullOrBlank()

    override suspend fun listVoices(): List<Voice> = PRESET_VOICES

    override suspend fun synthesize(
        sentence: Sentence,
        request: SynthesisRequest,
    ): SynthesisResult = withContext(Dispatchers.IO) {
        require(isConfigured()) { "MiMoEngine not configured (apiKey blank)" }
        require(sentence.text.length <= MAX_CHARS_PER_REQUEST) {
            "sentence too long (${sentence.text.length} chars > $MAX_CHARS_PER_REQUEST). " +
                "SentenceSegmenter should produce shorter sentences."
        }

        val started = System.currentTimeMillis()
        val responseBody = executeRequest(sentence, request)
        val elapsed = System.currentTimeMillis() - started

        parseResponse(responseBody, elapsed)
    }

    private fun executeRequest(sentence: Sentence, request: SynthesisRequest): String {
        // 在此处读最新 key / baseUrl;不缓存,避免用户设置页改了不生效
        val apiKey = apiKeyProvider().orEmpty()
        require(apiKey.isNotBlank()) { "MiMoEngine apiKey blank (race with isConfigured?)" }
        val baseUrl = baseUrlProvider().ifBlank { DEFAULT_BASE_URL }

        val payload = buildPayload(sentence, request, stream = false)
        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            // OkHttp's Response.body() is @Nullable in Java; Kotlin's nullability inference flips
            // between null and non-null for this platform-typed call depending on context. Bind
            // it to an explicit nullable local so the safe call chain below is unambiguous.
            val responseBody: okhttp3.ResponseBody? = response.body
            val body: String = responseBody?.string().orEmpty()
            if (!response.isSuccessful) {
                logcat(LogPriority.ERROR) { "[MimoEngine] HTTP ${response.code}: ${body.take(500)}" }
                throw MimoException("HTTP ${response.code}: ${body.take(200)}")
            }
            return body
        }
    }

    /**
     * 构造请求 payload。
     *
     * 关键：messages 必须包含 assistant role（user=风格，assistant=文本）
     */
    private fun buildPayload(
        sentence: Sentence,
        request: SynthesisRequest,
        stream: Boolean,
    ): String {
        val userMsg = request.style?.takeIf { it.isNotBlank() }
            ?: "请用自然的语气朗读以下文本"

        return buildJsonObject {
            put("model", model)
            put("stream", stream)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", userMsg)
                }
                addJsonObject {
                    put("role", "assistant")
                    put("content", sentence.text)
                }
            }
            putJsonObject("audio") {
                put("voice", request.voice)
                put("format", "mp3")
            }
        }.toString()
    }

    /**
     * 解析响应。
     *
     * 响应结构：
     * {
     *   "choices": [{
     *     "message": {
     *       "audio": {
     *         "id": "...",
     *         "data": "base64-encoded-mp3",
     *         "expires_at": null
     *       }
     *     },
     *     "finish_reason": "stop"
     *   }]
     * }
     */
    internal fun parseResponse(body: String, elapsedMs: Long): SynthesisResult {
        val json = Json.parseToJsonElement(body).jsonObject
        val audio = json["choices"]
            ?.jsonArray
            ?.get(0)
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("audio") as? JsonObject
            ?: throw MimoException("response missing audio field: ${body.take(200)}")

        val base64Data = audio["data"]?.jsonPrimitive?.content
            ?: throw MimoException("response missing audio.data: ${body.take(200)}")

        val mp3Bytes = Base64.getDecoder().decode(base64Data)

        // 估算时长：MiMo 固定 24kHz 单声道，比特率约 66 kb/s
        // 更准确的方案：解码 MP3 帧头（Phase 2 优化）
        val estimatedDurationMs = (mp3Bytes.size * 8L * 1000) / 66000

        return SynthesisResult(
            audioData = mp3Bytes,
            format = AudioFormat.MP3,
            sampleRate = MIMO_SAMPLE_RATE,
            channels = MIMO_CHANNELS,
            durationMs = estimatedDurationMs,
            elapsedMs = elapsedMs,
        )
    }

    companion object {
        /**
         * MiMo 默认 base URL。Token Plan 用户可在设置页覆盖。
         */
        const val DEFAULT_BASE_URL = "https://api.xiaomimimo.com"

        /**
         * MiMo 默认音色(冰糖:甜美可爱女声)。
         */
        const val DEFAULT_VOICE_ID = "冰糖"

        /**
         * MiMo 固定音频参数（Phase 0 实测）
         */
        const val MIMO_SAMPLE_RATE = 24000
        const val MIMO_CHANNELS = 1

        /**
         * 单次请求字符上限（保守值，5MB 响应封顶对应 ~1500 字）
         */
        const val MAX_CHARS_PER_REQUEST = 1500

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * 预设音色列表（与 VoxEngine 文档一致）
         *
         * 字段含义：id (API 用) / name (显示) / language / description
         */
        val PRESET_VOICES: List<Voice> = listOf(
            Voice("冰糖", "冰糖", "zh", VoiceType.PRESET, "甜美可爱女声"),
            Voice("茉莉", "茉莉", "zh", VoiceType.PRESET, "温柔知性女声"),
            Voice("苏打", "苏打", "zh", VoiceType.PRESET, "活力阳光男声"),
            Voice("白桦", "白桦", "zh", VoiceType.PRESET, "沉稳磁性男声"),
            Voice("Mia", "Mia", "en", VoiceType.PRESET, "English female"),
            Voice("Chloe", "Chloe", "en", VoiceType.PRESET, "English female"),
            Voice("Milo", "Milo", "en", VoiceType.PRESET, "English male"),
            Voice("Dean", "Dean", "en", VoiceType.PRESET, "English male"),
        )

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * MiMo API 异常
 */
class MimoException(message: String) : RuntimeException(message)

package koharia.tts

import kotlinx.coroutines.flow.Flow

/**
 * TTS 引擎抽象接口。
 *
 * 实现：
 * - [MimoEngine] — MiMo TTS（Phase 1）
 * - [EdgeEngine] — Microsoft Edge TTS（Phase 3，免费备用）
 *
 * 设计原则：
 * - 单引擎单次合成一个 Sentence（不是整段）—— 配合 MiMo 5MB 响应封顶
 * - 返回 [Flow] 支持流式和缓存复用
 * - 失败可重试（指数退避）由调用方控制
 */
interface TtsEngine {

    /**
     * 引擎标识。
     *
     * 用于：
     * - 缓存目录分级
     * - Injekt 单例 key
     * - 日志/调试
     */
    val engineId: String

    /**
     * 引擎显示名（用于设置页面）
     */
    val displayName: String

    /**
     * 是否已配置（API key 等）。
     *
     * false 时所有 synthesize 调用都会立即失败（fail-fast），
     * 避免等待网络超时。
     */
    fun isConfigured(): Boolean

    /**
     * 获取可用音色列表。
     *
     * Phase 1: 返回硬编码预设音色（冰糖、茉莉、苏打、白桦 等）
     * Phase 3: 从 MiMo API 动态获取（含用户克隆/设计音色）
     */
    suspend fun listVoices(): List<Voice>

    /**
     * 把一句文本合成成 MP3 字节。
     *
     * @param sentence 要朗读的句子
     * @param request 合成参数
     * @return 合成结果，含音频字节和元数据
     */
    suspend fun synthesize(
        sentence: Sentence,
        request: SynthesisRequest,
    ): SynthesisResult
}

/**
 * 引擎请求参数
 */
data class SynthesisRequest(
    /**
     * 音色 ID 或名称（如 "冰糖"、"alloy"）
     */
    val voice: String,

    /**
     * 风格标签（如 "温柔"、"开心"），可空
     */
    val style: String? = null,

    /**
     * 语速倍率，1.0 = 正常，0.5 = 半速，2.0 = 2 倍速
     *
     * 注意：MiMo 不直接接受语速参数，会通过 prompt 指令或后处理实现
     * （详见 [MimoEngine] 实现）
     */
    val speed: Float = 1.0f,
)

/**
 * 合成结果
 */
data class SynthesisResult(
    /**
     * MP3 字节
     */
    val audioData: ByteArray,

    /**
     * 音频格式
     */
    val format: AudioFormat,

    /**
     * 采样率（MiMo 固定 24000 Hz）
     */
    val sampleRate: Int,

    /**
     * 声道数（MiMo 固定 1）
     */
    val channels: Int,

    /**
     * 实际音频时长（毫秒）
     *
     * MP3 需要解码才能算准确，Phase 1 暂用字节数估算，Phase 3 优化
     */
    val durationMs: Long,

    /**
     * 合成耗时（毫秒）
     */
    val elapsedMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SynthesisResult) return false
        return audioData.contentEquals(other.audioData) &&
            format == other.format &&
            sampleRate == other.sampleRate &&
            channels == other.channels &&
            durationMs == other.durationMs &&
            elapsedMs == other.elapsedMs
    }

    override fun hashCode(): Int {
        var result = audioData.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + elapsedMs.hashCode()
        return result
    }
}

enum class AudioFormat {
    MP3,
    WAV,
    PCM,
}

data class Voice(
    /**
     * 音色 ID（传给 API 用）
     */
    val id: String,

    /**
     * 显示名
     */
    val name: String,

    /**
     * 语言代码（zh / en / ja）
     */
    val language: String,

    /**
     * 音色类型
     */
    val type: VoiceType,

    /**
     * 描述（可选，给用户看的，如 "甜美可爱女声"）
     */
    val description: String? = null,
)

enum class VoiceType {
    /** 引擎预设音色 */
    PRESET,

    /** 用户克隆音色（基于音频样本） */
    CLONE,

    /** 用户设计音色（基于文字描述） */
    DESIGN,
}

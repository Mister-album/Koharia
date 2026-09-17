package koharia.tts

import java.io.File
import java.security.MessageDigest

/**
 * TTS 音频磁盘缓存。
 *
 * 设计：
 * - 缓存目录：`<cacheDir>/tts/`
 * - 缓存键：md5(voice + "|" + style + "|" + text)
 * - 文件命名：用 cacheKey 前 2 位作为一级目录（避免单目录文件过多）
 *
 * Phase 0 发现：MiMo 音频 `expires_at=null`，**永不过期**，可永久缓存。
 *
 * Phase 1: 单进程使用
 * Phase 2: 加 LRU 内存索引 + 容量上限
 */
class TtsCache(
    /**
     * 缓存根目录（建议 `context.cacheDir/tts/`）
     */
    private val cacheDir: File,
) {

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    /**
     * 取缓存（命中返回字节，未命中返回 null）
     */
    fun get(voice: String, style: String?, text: String): ByteArray? {
        val file = fileFor(voice, style, text)
        if (!file.exists()) return null
        return file.readBytes()
    }

    /**
     * 写缓存
     */
    fun put(voice: String, style: String?, text: String, audioData: ByteArray) {
        val file = fileFor(voice, style, text)
        file.parentFile?.mkdirs()
        file.writeBytes(audioData)
    }

    /**
     * 检查是否存在
     */
    fun exists(voice: String, style: String?, text: String): Boolean {
        return fileFor(voice, style, text).exists()
    }

    /**
     * 清空所有缓存
     *
     * 用于设置页"清除缓存"按钮
     */
    fun clearAll() {
        cacheDir.walkBottomUp().forEach { it.delete() }
        cacheDir.mkdirs()
    }

    /**
     * 当前缓存大小（字节）
     */
    fun sizeBytes(): Long {
        return cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun fileFor(voice: String, style: String?, text: String): File {
        val key = cacheKey(voice, style, text)
        val (prefix, rest) = key.splitAt(2)
        return File(cacheDir, "$prefix/$rest.mp3")
    }

    private fun cacheKey(voice: String, style: String?, text: String): String {
        val stylePart = style?.takeIf { it.isNotBlank() } ?: ""
        val raw = "$voice|$stylePart|$text"
        return md5(raw)
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun String.splitAt(index: Int): Pair<String, String> {
        require(index in 0..length) { "splitAt index out of bounds: $index" }
        return substring(0, index) to substring(index)
    }
}

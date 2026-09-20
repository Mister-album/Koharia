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
 * 容量与并发（review 修复）：
 * - **容量上限**：[maxBytes] 默认 [DEFAULT_MAX_BYTES]；每次 `put` 后按 LRU 淘汰，
 *   读取命中会刷新文件 mtime，保证热句不被误删。旧实现只增不减，长期使用会累积
 *   大量 MP3。
 * - **原子写入**：先写同目录临时文件再 `renameTo`，避免并发 `get` 读到半写入文件。
 *   写入失败时临时文件一定被清理。
 * - **线程安全**：`SentencePrefetcher` 会从最多 5 个协程并发 `put`，`get` 来自播放
 *   协程，故所有变更路径经 [lock] 串行。
 */
class TtsCache(
    /**
     * 缓存根目录（建议 `context.cacheDir/tts/`）
     */
    private val cacheDir: File,
    /**
     * 缓存容量上限（字节）。超出后按 LRU 淘汰最久未使用的文件。
     */
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    private val lock = Any()

    /**
     * 缓存占用字节数的**缓存值**，仅用于 [put] 路径的淘汰判定。
     *
     * `-1` 表示未知，需要一次全树遍历重建。此前实现对**每次 put** 都做一次
     * `walkTopDown()` 全树求和：缓存装满 256 MiB 大量小 MP3 时，每合成一句就要
     * O(文件数) 次 stat —— 明显拖慢播放。现在只在进程内首次（或 [clearAll] 后）遍历一次。
     *
     * 公开的 [sizeBytes] 仍返回精确值（供 UI / 测试），不走这个缓存。
     */
    private var cachedBytes: Long = -1L

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    /**
     * 取缓存（命中返回字节，未命中返回 null）。
     *
     * 命中时刷新 mtime，作为 LRU 的"最近使用"信号。
     */
    fun get(voice: String, style: String?, text: String): ByteArray? {
        val file = fileFor(voice, style, text)
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        // mtime 刷新（LRU 的"最近使用"信号）必须在锁内：否则并发淘汰可能正好在
        // "读到字节 → 刷新 mtime"之间把这条热数据当成最旧文件删掉。
        synchronized(lock) {
            runCatching { file.setLastModified(System.currentTimeMillis()) }
        }
        return bytes
    }

    /**
     * 写缓存：原子写入 + 触发容量淘汰。
     */
    fun put(voice: String, style: String?, text: String, audioData: ByteArray) {
        val file = fileFor(voice, style, text)
        synchronized(lock) {
            file.parentFile?.mkdirs()
            val previousLength = if (file.exists()) file.length() else 0L
            writeAtomically(file, audioData)
            if (cachedBytes >= 0) {
                cachedBytes = (cachedBytes - previousLength + audioData.size).coerceAtLeast(0L)
            }
            evictIfNeeded(protect = file)
        }
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
        synchronized(lock) {
            cacheDir.walkBottomUp().forEach { it.delete() }
            cacheDir.mkdirs()
            cachedBytes = 0L
        }
    }

    /**
     * 当前缓存大小（字节）
     */
    fun sizeBytes(): Long {
        return cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** 锁内的容量读取：优先用增量维护的 [cachedBytes]，未知时才全树遍历一次。 */
    private fun sizeBytesLocked(): Long {
        if (cachedBytes < 0) {
            cachedBytes = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        return cachedBytes
    }

    /**
     * 原子写入：同目录临时文件 → rename。
     *
     * Java 的 [File.renameTo] 在 Android/Linux 上映射为 `rename(2)`，会原子覆盖已存在
     * 目标；为兼容不覆盖目标的文件系统，失败时先删目标再重试，最后才回退就地写。
     * 任何路径下临时文件都在 `finally` 里清理。
     */
    private fun writeAtomically(target: File, data: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.tmp-${System.nanoTime()}")
        try {
            temp.writeBytes(data)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) {
                    target.writeBytes(data)
                }
            }
        } finally {
            if (temp.exists()) {
                temp.delete()
            }
        }
    }

    /**
     * 超出 [maxBytes] 时按 LRU（mtime 最旧优先）淘汰，直到回到上限内。
     *
     * [protect] 是本次刚写完的文件，永不淘汰它 —— 否则"写进去立刻被删"会让缓存失效。
     * 顺带清理淘汰后变空的一级目录。
     */
    private fun evictIfNeeded(protect: File) {
        var total = sizeBytesLocked()
        if (total <= maxBytes) return
        val candidates = cacheDir.walkTopDown()
            .filter { it.isFile && it.absolutePath != protect.absolutePath }
            .sortedBy { it.lastModified() }
            .toList()
        for (file in candidates) {
            if (total <= maxBytes) break
            val length = file.length()
            if (file.delete()) {
                total -= length
                cachedBytes = (cachedBytes - length).coerceAtLeast(0L)
                file.parentFile
                    ?.takeIf { it.absolutePath != cacheDir.absolutePath && it.listFiles()?.isEmpty() == true }
                    ?.delete()
            }
        }
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

    companion object {
        /** 默认容量上限：256 MiB。 */
        const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024
    }
}

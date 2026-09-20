package koharia.epub.locator

/**
 * EPUB href 拆分 + 解码结果。
 *
 * @param path 资源路径（已百分号解码，`+` 保持原样）
 * @param fragment 片段锚点（已百分号解码）；无片段或片段为空时为 `null`
 */
data class DecodedEpubHref(
    val path: String,
    val fragment: String?,
)

/**
 * EPUB href 的 path / fragment 拆分与解码。
 *
 * 为什么不能直接用 [java.net.URLDecoder] 解整条 href：
 *  - 它会把 `+` 变成空格，破坏合法文件名；
 *  - 它会提前解掉 `%2F`、`%3F` 等**保留字符**，改变路径分段语义。
 *
 * 本对象的正确顺序是：**先按 URI 规则拆分 path 与 fragment，再各自百分号解码**。
 *
 * 片段分隔符既可能是字面 `#`，也可能是百分号编码的 `%23`（本仓库同源 EPUB 的
 * TOC href 就把分隔符写成 `%23`，见 [EpubHrefDecoder] 的调用方注释），
 * 且片段里可能是**未编码的非 ASCII**（中文小节名），因此不能用 `java.net.URI`
 * 直接解析（非 ASCII 会抛异常）。
 *
 * 纯 Kotlin/JVM 实现（不依赖 Android），便于单元测试。
 */
object EpubHrefDecoder {

    fun decode(rawHref: String): DecodedEpubHref {
        if (rawHref.isEmpty()) return DecodedEpubHref(path = "", fragment = null)

        val delimiter = findFragmentDelimiter(rawHref)
            ?: return DecodedEpubHref(path = percentDecode(rawHref), fragment = null)

        val (start, length) = delimiter
        val rawPath = rawHref.substring(0, start)
        val rawFragment = rawHref.substring(start + length)
        val fragment = percentDecode(rawFragment).takeIf { it.isNotBlank() }
        return DecodedEpubHref(path = percentDecode(rawPath), fragment = fragment)
    }

    /** 返回（分隔符起始下标, 分隔符长度）；无分隔符返回 `null`。 */
    private fun findFragmentDelimiter(raw: String): Pair<Int, Int>? {
        val hashIndex = raw.indexOf('#')
        val encodedHashIndex = indexOfEncodedHash(raw)
        return when {
            hashIndex < 0 && encodedHashIndex < 0 -> null
            hashIndex < 0 -> encodedHashIndex to ENCODED_HASH_LENGTH
            encodedHashIndex < 0 -> hashIndex to 1
            hashIndex <= encodedHashIndex -> hashIndex to 1
            else -> encodedHashIndex to ENCODED_HASH_LENGTH
        }
    }

    /** 找到 `%23`（`#` 的百分号编码）首次出现的下标；找不到返回 -1。 */
    private fun indexOfEncodedHash(raw: String): Int {
        var i = 0
        while (i <= raw.length - ENCODED_HASH_LENGTH) {
            if (
                raw[i] == '%' &&
                hexValue(raw[i + 1]) == 2 &&
                hexValue(raw[i + 2]) == 3
            ) {
                return i
            }
            i++
        }
        return -1
    }

    /**
     * 百分号解码：只解 `%XX`，**不**把 `+` 当作空格。
     * 非法转义（截断 / 非十六进制）原样保留，绝不抛异常。
     */
    private fun percentDecode(input: String): String {
        if ('%' !in input) return input
        val out = java.io.ByteArrayOutputStream(input.length)
        // 字面量必须**整段**编码：逐 Char 写会让代理对（emoji 等 supplementary 字符）
        // 被拆成两个孤立的 UTF-16 code unit，各自被替换成 '?'。
        var literalStart = 0
        var i = 0
        while (i < input.length) {
            if (input[i] == '%' && i + 2 < input.length) {
                val hi = hexValue(input[i + 1])
                val lo = hexValue(input[i + 2])
                if (hi >= 0 && lo >= 0) {
                    if (literalStart < i) {
                        out.write(input.substring(literalStart, i).toByteArray(Charsets.UTF_8))
                    }
                    out.write((hi shl 4) or lo)
                    i += 3
                    literalStart = i
                    continue
                }
            }
            i++
        }
        if (literalStart < input.length) {
            out.write(input.substring(literalStart).toByteArray(Charsets.UTF_8))
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private const val ENCODED_HASH_LENGTH = 3
}

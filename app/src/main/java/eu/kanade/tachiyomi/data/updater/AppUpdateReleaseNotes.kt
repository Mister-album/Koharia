package eu.kanade.tachiyomi.data.updater

internal object AppUpdateReleaseNotes {

    fun select(content: String, language: String): String {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return normalized

        val selected = selectMarked(normalized, language)
            ?: selectLegacy(normalized, language)
            ?: normalized
        return selected
            .removeChecksumSection()
            .trimEdgeHorizontalRules()
    }

    private fun selectMarked(content: String, language: String): String? {
        val chineseStart = content.indexOf(CHINESE_MARKER)
        val englishStart = content.indexOf(ENGLISH_MARKER)
        val end = content.indexOf(END_MARKER)
        if (chineseStart < 0 || englishStart <= chineseStart || end <= englishStart) return null

        val chinese = content.substring(chineseStart + CHINESE_MARKER.length, englishStart).trim()
        val english = content.substring(englishStart + ENGLISH_MARKER.length, end).trim()
        return if (language.equals(CHINESE_LANGUAGE, ignoreCase = true)) {
            chinese.ifBlank { english }
        } else {
            english.ifBlank { chinese }
        }.takeIf(String::isNotBlank)
    }

    private fun selectLegacy(content: String, language: String): String? {
        return HORIZONTAL_RULE.findAll(content).firstNotNullOfOrNull { divider ->
            val chinese = content.substring(0, divider.range.first).trim()
            val english = content.substring(divider.range.last + 1).trim()
            if (!LEGACY_ENGLISH_HEADER.containsMatchIn(english)) return@firstNotNullOfOrNull null

            if (language.equals(CHINESE_LANGUAGE, ignoreCase = true)) chinese else english
        }
    }

    private fun String.removeChecksumSection(): String {
        val checksumHeading = CHECKSUM_HEADING.find(this) ?: return this
        return substring(0, checksumHeading.range.first)
    }

    private fun String.trimEdgeHorizontalRules(): String {
        val lines = lines().toMutableList()
        while (lines.firstOrNull()?.isBlank() == true) lines.removeAt(0)
        while (lines.lastOrNull()?.isBlank() == true) lines.removeAt(lines.lastIndex)
        if (lines.firstOrNull()?.matches(HORIZONTAL_RULE_LINE) == true) lines.removeAt(0)
        if (lines.lastOrNull()?.matches(HORIZONTAL_RULE_LINE) == true) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n").trim()
    }

    private const val CHINESE_LANGUAGE = "zh"
    private const val CHINESE_MARKER = "<!-- koharia-release-notes:zh -->"
    private const val ENGLISH_MARKER = "<!-- koharia-release-notes:en -->"
    private const val END_MARKER = "<!-- koharia-release-notes:end -->"
    private val HORIZONTAL_RULE_LINE = Regex("""\s*(?:(?:-\s*){3,}|(?:\*\s*){3,}|(?:_\s*){3,})""")
    private val HORIZONTAL_RULE = Regex("""(?m)^${HORIZONTAL_RULE_LINE.pattern}$""")
    private val LEGACY_ENGLISH_HEADER = Regex("""(?i)^#\s+Koharia\b""")
    private val CHECKSUM_HEADING = Regex("""(?im)^\s*(?:#{1,6}\s*)?Checksums?\s*$""")
}

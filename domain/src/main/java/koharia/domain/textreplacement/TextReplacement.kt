package koharia.domain.textreplacement

import com.google.re2j.Pattern
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

@Serializable
enum class ReplacementScope { GLOBAL, SERIES, BOOK }

@Serializable
data class ReplacementRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val pattern: String,
    val replacement: String = "",
    val regex: Boolean = false,
    val ignoreCase: Boolean = false,
    val enabled: Boolean = true,
    val issue: String? = null,
) {
    val key: String get() = Json.encodeToString(listOf(regex.toString(), ignoreCase.toString(), pattern))
}

@Serializable
data class ReplacementScopeState(
    val rules: List<ReplacementRule> = emptyList(),
    val inherit: Boolean = true,
    val enabled: Boolean = true,
    val excludedKeys: Set<String> = emptySet(),
)

@Serializable
data class TextReplacementContext(
    val connectionId: Long = 0,
    val ownerKey: String = "",
    val seriesKey: String = "",
    val bookKey: String = "",
) {
    fun key(scope: ReplacementScope): String = when (scope) {
        ReplacementScope.GLOBAL -> "global"
        ReplacementScope.SERIES -> Json.encodeToString(listOf(connectionId.toString(), ownerKey, seriesKey))
        ReplacementScope.BOOK -> Json.encodeToString(listOf(connectionId.toString(), ownerKey, seriesKey, bookKey))
    }
}

data class EffectiveReplacementRule(val rule: ReplacementRule, val scope: ReplacementScope)

data class EffectiveRuleSet(val rules: List<EffectiveReplacementRule>) {
    val fingerprint: String = MessageDigest.getInstance("SHA-256")
        .digest(
            Json.encodeToString(
                rules.map {
                    listOf(
                        it.scope.name,
                        it.rule.pattern,
                        it.rule.replacement,
                        it.rule.regex.toString(),
                        it.rule.ignoreCase.toString(),
                    )
                },
            ).toByteArray(),
        )
        .joinToString("") { "%02x".format(it) }

    companion object {
        val EMPTY = EffectiveRuleSet(emptyList())

        fun resolve(states: Map<ReplacementScope, ReplacementScopeState>, enabled: Boolean = true): EffectiveRuleSet {
            if (!enabled || states[ReplacementScope.BOOK]?.enabled == false) return EMPTY
            var inherited = emptyList<EffectiveReplacementRule>()
            ReplacementScope.entries.forEach { scope ->
                val state = states[scope] ?: ReplacementScopeState()
                if (!state.inherit) inherited = emptyList()
                inherited = inherited.filterNot { it.rule.key in state.excludedKeys }
                val own = state.rules.filter {
                    it.enabled && it.issue == null
                }.map { EffectiveReplacementRule(it, scope) }
                val keys = own.mapTo(mutableSetOf()) { it.rule.key }
                inherited = own + inherited.filterNot { it.rule.key in keys }
            }
            return EffectiveRuleSet(inherited)
        }
    }
}

data class TextReplacementEdit(val start: Int, val end: Int, val text: String, val ruleId: String)

data class TextReplacementResult(val original: String, val text: String, val edits: List<TextReplacementEdit>) {
    fun toOriginal(offset: Int): Int {
        var delta = 0
        for (edit in edits) {
            val start = edit.start + delta
            if (offset < start) break
            if (offset < start + edit.text.length) return edit.start
            delta += edit.text.length - (edit.end - edit.start)
        }
        return (offset - delta).coerceIn(0, original.length)
    }

    fun toDisplay(offset: Int): Int {
        var delta = 0
        for (edit in edits) {
            if (offset < edit.start) break
            if (offset < edit.end) return edit.start + delta
            delta += edit.text.length - (edit.end - edit.start)
        }
        return (offset + delta).coerceIn(0, text.length)
    }
}

/** All matches use the original input. Priority is encoded by effective rule order. */
class TextReplacementEngine(private val rules: EffectiveRuleSet) {
    private val compiled = rules.rules.map { it.rule to compile(it.rule) }

    fun replace(input: String): TextReplacementResult {
        val accepted = java.util.TreeMap<Int, TextReplacementEdit>()
        compiled.forEach { (rule, pattern) ->
            val matcher = pattern.matcher(input)
            while (matcher.find()) {
                require(matcher.start() != matcher.end()) { "zero_length_match" }
                val start = matcher.start()
                val end = matcher.end()
                if ((start until end).any { input[it] == '\n' || input[it] == '\r' }) continue
                if ((accepted.floorEntry(start)?.value?.end ?: -1) > start ||
                    (accepted.ceilingKey(start) ?: Int.MAX_VALUE) < end
                ) {
                    continue
                }
                val text = if (rule.regex) {
                    expand(rule.replacement, matcher.groupCount()) {
                        matcher.group(it).orEmpty()
                    }
                } else {
                    rule.replacement
                }
                accepted[start] = TextReplacementEdit(start, end, text, rule.id)
            }
        }
        val edits = accepted.values.toList()
        val output = StringBuilder(input.length)
        var cursor = 0
        edits.forEach {
            output.append(input, cursor, it.start).append(it.text)
            cursor = it.end
        }
        output.append(input, cursor, input.length)
        return TextReplacementResult(input, output.toString(), edits)
    }

    companion object {
        fun validate(rule: ReplacementRule): String? = runCatching { compile(rule) }.exceptionOrNull()?.message

        private fun compile(rule: ReplacementRule): Pattern {
            require(rule.pattern.isNotEmpty()) { "empty_pattern" }
            val pattern = Pattern.compile(
                if (rule.regex) rule.pattern else Pattern.quote(rule.pattern),
                if (rule.ignoreCase) Pattern.CASE_INSENSITIVE else 0,
            )
            // RE2 has no lookaround. Empty paths can depend only on anchors and word boundaries.
            for (probe in listOf("", "a", " a", "a ", "\na\n", " ", "中")) {
                val matcher = pattern.matcher(probe)
                while (matcher.find()) require(matcher.start() != matcher.end()) { "zero_length_match" }
            }
            if (rule.regex) expand(rule.replacement, pattern.groupCount()) { "" }
            return pattern
        }

        private fun expand(template: String, groups: Int, group: (Int) -> String): String = buildString {
            var i = 0
            while (i < template.length) {
                when (val c = template[i++]) {
                    '\\' -> {
                        require(i < template.length) { "invalid_escape" }
                        append(template[i++])
                    }
                    '$' -> {
                        val start = i
                        while (i < template.length && template[i] in '0'..'9') i++
                        require(i > start) { "invalid_group" }
                        val number = template.substring(start, i).toIntOrNull()
                        require(number != null && number in 0..groups) { "invalid_group" }
                        append(group(number))
                    }
                    else -> append(c)
                }
            }
        }
    }
}

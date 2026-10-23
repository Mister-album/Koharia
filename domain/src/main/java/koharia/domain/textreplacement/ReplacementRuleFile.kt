package koharia.domain.textreplacement

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ReplacementRuleGroup(val scope: ReplacementScope, val state: ReplacementScopeState)

@Serializable
data class ReplacementRuleFile(
    val format: String = "koharia-text-replacement",
    val version: Int = 1,
    val groups: List<ReplacementRuleGroup>,
)

data class ReplacementImport(val groups: List<ReplacementRuleGroup>, val legado: Boolean)

object ReplacementRuleFiles {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(groups: List<ReplacementRuleGroup>): String = json.encodeToString(ReplacementRuleFile(groups = groups))

    fun decode(text: String): ReplacementImport {
        require(text.length <= 2 * 1024 * 1024) { "file_too_large" }
        val root = json.parseToJsonElement(text)
        if (root is JsonObject && root["format"]?.jsonPrimitive?.contentOrNull == "koharia-text-replacement") {
            val file = json.decodeFromString<ReplacementRuleFile>(text)
            require(file.version == 1) { "unsupported_version" }
            require(file.groups.sumOf { it.state.rules.size } <= 1000) { "too_many_rules" }
            return ReplacementImport(
                file.groups.map { group ->
                    group.copy(state = group.state.copy(rules = group.state.rules.map(::validated)))
                },
                false,
            )
        }
        val entries = when (root) {
            is JsonArray -> root
            is JsonObject -> JsonArray(listOf(root))
            else -> error("unsupported_format")
        }
        require(entries.size <= 1000) { "too_many_rules" }
        val rules = entries.map { element ->
            val entry = element as? JsonObject ?: error("unsupported_format")
            fun string(key: String) = entry[key]?.jsonPrimitive?.contentOrNull.orEmpty()
            fun boolean(key: String, default: Boolean) = entry[key]?.jsonPrimitive?.booleanOrNull ?: default
            require("pattern" in entry && "replacement" in entry) { "unsupported_format" }
            val issue = when {
                boolean("isTitle", false) -> "legado_title"
                !boolean("isContent", true) -> "legado_not_content"
                string("scope").isNotBlank() || string("excludeScope").isNotBlank() -> "legado_scope"
                string("timeoutMillisecond").toLongOrNull()?.let { it > 0 } == true -> "legado_timeout"
                entry.keys.any { it !in legadoFields } -> "legado_unknown_option"
                else -> null
            }
            val rule = validated(
                ReplacementRule(
                    name = string("name"),
                    pattern = string("pattern"),
                    replacement = string("replacement"),
                    regex = boolean("isRegex", false),
                    enabled = boolean("isEnabled", true),
                    issue = issue,
                ),
            )
            (entry["order"]?.jsonPrimitive?.intOrNull ?: 0) to rule
        }.sortedBy { it.first }.map { it.second }
        return ReplacementImport(
            listOf(
                ReplacementRuleGroup(
                    ReplacementScope.GLOBAL,
                    ReplacementScopeState(rules = rules),
                ),
            ),
            true,
        )
    }

    private fun validated(rule: ReplacementRule): ReplacementRule {
        val issue = rule.issue ?: TextReplacementEngine.validate(rule)
        return rule.copy(enabled = rule.enabled && issue == null, issue = issue)
    }

    /** Stable UUID and match-key collisions both require an explicit overwrite choice. */
    fun merge(
        existing: ReplacementScopeState,
        incoming: ReplacementScopeState,
        overwrite: Set<String>,
        importSettings: Boolean = false,
    ): ReplacementScopeState {
        val rules = existing.rules.toMutableList()
        incoming.rules.forEach { rule ->
            val collisions = rules.filter { it.id == rule.id || it.key == rule.key }
            if (collisions.isEmpty()) {
                rules += rule
            } else if (rule.id in overwrite) {
                val index = rules.indexOf(collisions.first())
                rules.removeAll(collisions.toSet())
                rules.add(index.coerceAtMost(rules.size), rule)
            }
        }
        return existing.copy(
            rules = rules,
            excludedKeys = if (importSettings) existing.excludedKeys + incoming.excludedKeys else existing.excludedKeys,
            inherit = if (importSettings) incoming.inherit else existing.inherit,
            enabled = if (importSettings) incoming.enabled else existing.enabled,
        )
    }

    private val legadoFields = setOf(
        "id",
        "name",
        "group",
        "pattern",
        "replacement",
        "scope",
        "excludeScope",
        "isEnabled",
        "isRegex",
        "isTitle",
        "isContent",
        "order",
        "timeoutMillisecond",
    )
}

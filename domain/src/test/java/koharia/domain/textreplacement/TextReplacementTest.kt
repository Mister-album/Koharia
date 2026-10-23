package koharia.domain.textreplacement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextReplacementTest {
    @Test
    fun `importing rules without settings preserves active inherited rules`() {
        val inherited = ReplacementRule(pattern = "parent", replacement = "replaced")
        val importedRule = ReplacementRule(pattern = "new", replacement = "imported")
        val existing = ReplacementScopeState(excludedKeys = setOf("existing-exclusion"))
        val incoming = ReplacementScopeState(
            rules = listOf(importedRule),
            inherit = false,
            enabled = false,
            excludedKeys = setOf(inherited.key),
        )

        val merged = ReplacementRuleFiles.merge(existing, incoming, emptySet(), importSettings = false)
        val effective = EffectiveRuleSet.resolve(
            mapOf(
                ReplacementScope.GLOBAL to ReplacementScopeState(rules = listOf(inherited)),
                ReplacementScope.BOOK to merged,
            ),
        )

        assertEquals(existing.excludedKeys, merged.excludedKeys)
        assertTrue(merged.inherit)
        assertTrue(merged.enabled)
        assertEquals("replaced imported", TextReplacementEngine(effective).replace("parent new").text)
    }

    @Test
    fun `importing settings merges exclusions and disables the selected inherited rule`() {
        val inherited = ReplacementRule(pattern = "parent", replacement = "replaced")
        val existing = ReplacementScopeState(excludedKeys = setOf("existing-exclusion"))
        val incoming = ReplacementScopeState(excludedKeys = setOf(inherited.key))

        val merged = ReplacementRuleFiles.merge(existing, incoming, emptySet(), importSettings = true)
        val effective = EffectiveRuleSet.resolve(
            mapOf(
                ReplacementScope.GLOBAL to ReplacementScopeState(rules = listOf(inherited)),
                ReplacementScope.BOOK to merged,
            ),
        )

        assertEquals(existing.excludedKeys + inherited.key, merged.excludedKeys)
        assertEquals("parent", TextReplacementEngine(effective).replace("parent").text)
    }

    @Test
    fun `matches cannot span source line breaks even with dotall`() {
        val result = engine(ReplacementRule(pattern = "(?s)甲.*乙", replacement = "changed", regex = true))
            .replace("甲\n乙")
        assertEquals("甲\n乙", result.text)
        assertTrue(result.edits.isEmpty())
    }

    @Test
    fun `import can restore scope switches without overriding existing conflicts`() {
        val original = ReplacementRule(pattern = "old", replacement = "kept")
        val existing = ReplacementScopeState(listOf(original))
        val imported =
            ReplacementScopeState(listOf(original.copy(replacement = "imported")), inherit = false, enabled = false)
        val result = ReplacementRuleFiles.merge(existing, imported, emptySet(), importSettings = true)
        assertFalse(result.inherit)
        assertFalse(result.enabled)
        assertEquals("kept", result.rules.single().replacement)
    }

    @Test
    fun `renaming a rule does not invalidate content caches and zero width boundaries are rejected`() {
        val rule = ReplacementRule(pattern = "old", replacement = "new")
        fun fingerprint(
            value: ReplacementRule,
        ) = EffectiveRuleSet(listOf(EffectiveReplacementRule(value, ReplacementScope.GLOBAL))).fingerprint
        assertEquals(fingerprint(rule), fingerprint(rule.copy(name = "renamed")))
        assertNotNull(TextReplacementEngine.validate(ReplacementRule(pattern = "\\b", regex = true)))
    }

    private fun engine(vararg rules: ReplacementRule) = TextReplacementEngine(
        EffectiveRuleSet.resolve(mapOf(ReplacementScope.GLOBAL to ReplacementScopeState(rules.toList()))),
    )

    @Test
    fun `more specific rule wins without cascading output`() {
        val global = ReplacementRule(pattern = "甲", replacement = "乙")
        val book = ReplacementRule(pattern = "甲", replacement = "丙")
        val rules = EffectiveRuleSet.resolve(
            mapOf(
                ReplacementScope.GLOBAL to
                    ReplacementScopeState(listOf(global, ReplacementRule(pattern = "丙", replacement = "丁"))),
                ReplacementScope.BOOK to ReplacementScopeState(listOf(book)),
            ),
        )
        assertEquals("丙丁", TextReplacementEngine(rules).replace("甲丙").text)
    }

    @Test
    fun `disabled override falls back and exclusion suppresses parent`() {
        val global = ReplacementRule(pattern = "a", replacement = "b")
        val parent = ReplacementScopeState(listOf(global))
        val book = ReplacementScopeState(listOf(global.copy(id = "book", replacement = "c", enabled = false)))
        assertEquals(
            listOf(global),
            EffectiveRuleSet.resolve(
                mapOf(
                    ReplacementScope.GLOBAL to parent,
                    ReplacementScope.BOOK to book,
                ),
            ).rules.map { it.rule },
        )
        assertTrue(
            EffectiveRuleSet.resolve(
                mapOf(
                    ReplacementScope.GLOBAL to parent,
                    ReplacementScope.BOOK to book.copy(excludedKeys = setOf(global.key)),
                ),
            ).rules.isEmpty(),
        )
        assertTrue(
            EffectiveRuleSet.resolve(
                mapOf(
                    ReplacementScope.GLOBAL to parent,
                    ReplacementScope.BOOK to book.copy(inherit = false),
                ),
            ).rules.isEmpty(),
        )
        assertTrue(EffectiveRuleSet.resolve(mapOf(ReplacementScope.GLOBAL to parent), enabled = false).rules.isEmpty())
    }

    @Test
    fun `priority applies to overlapping matches even if lower priority starts earlier`() {
        assertEquals(
            "aX",
            engine(
                ReplacementRule(pattern = "bc", replacement = "X"),
                ReplacementRule(pattern = "ab", replacement = "Y"),
            ).replace("abc").text,
        )
    }

    @Test
    fun `literal dollar slash unicode deletion and case folding`() {
        assertEquals(
            "$\\🙂",
            engine(
                ReplacementRule(pattern = "A", replacement = "$\\🙂", ignoreCase = true),
                ReplacementRule(pattern = "广告"),
            ).replace("a广告").text,
        )
    }

    @Test
    fun `regex captured groups and rejection`() {
        assertEquals(
            "12:卷",
            engine(ReplacementRule(pattern = "(卷)([0-9]+)", replacement = "$2:$1", regex = true)).replace("卷12").text,
        )
        listOf("(?<=a)b", "(a)\\1", "a*", "[").forEach {
            assertNotNull(TextReplacementEngine.validate(ReplacementRule(pattern = it, regex = true)))
        }
        assertNotNull(
            TextReplacementEngine.validate(ReplacementRule(pattern = "(a)", replacement = "$2", regex = true)),
        )
    }

    @Test
    fun `coordinate mappings account for deletion and expansion`() {
        val result = engine(
            ReplacementRule(pattern = "广告"),
            ReplacementRule(pattern = "甲", replacement = "甲乙丙"),
        ).replace("前广告中甲后")
        assertEquals("前中甲乙丙后", result.text)
        assertEquals(2, result.toDisplay(4))
        assertEquals(4, result.toOriginal(3))
        assertEquals(6, result.toOriginal(result.text.length))
    }

    @Test
    fun `native JSON round trip excludes all binding information`() {
        val state =
            ReplacementScopeState(
                listOf(ReplacementRule(pattern = "old", replacement = "new")),
                inherit = false,
                excludedKeys = setOf("key"),
            )
        val groups = listOf(ReplacementRuleGroup(ReplacementScope.BOOK, state))
        val encoded = ReplacementRuleFiles.encode(groups)
        assertEquals(groups, ReplacementRuleFiles.decode(encoded).groups)
        assertFalse(encoded.contains("connectionId"))
        assertFalse(encoded.contains("ownerKey"))
    }

    @Test
    fun `legado unsupported rules remain disabled and conflicts preserve existing rules`() {
        val result = ReplacementRuleFiles.decode(
            """[{"name":"title","pattern":"a","replacement":"b","isTitle":true,"isRegex":false,"isEnabled":true}]""",
        )
        assertTrue(result.legado)
        val rule = result.groups.single().state.rules.single()
        assertFalse(rule.enabled)
        assertEquals("legado_title", rule.issue)
        val existing = ReplacementScopeState(listOf(rule.copy(replacement = "existing")))
        assertEquals(existing, ReplacementRuleFiles.merge(existing, result.groups.single().state, emptySet()))
        assertEquals(
            "b",
            ReplacementRuleFiles.merge(
                existing,
                result.groups.single().state,
                setOf(rule.id),
            ).rules.single().replacement,
        )
    }

    @Test
    fun `contexts isolate accounts connections series and books`() {
        val context = TextReplacementContext(1, "owner", "series", "book")
        val keys = listOf(
            context,
            context.copy(connectionId = 2),
            context.copy(ownerKey = "other"),
            context.copy(seriesKey = "other"),
            context.copy(bookKey = "other"),
        ).map {
            it.key(ReplacementScope.BOOK)
        }
        assertEquals(5, keys.distinct().size)
        assertEquals(context.key(ReplacementScope.GLOBAL), context.copy(connectionId = 2).key(ReplacementScope.GLOBAL))
    }
}

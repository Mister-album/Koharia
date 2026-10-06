package koharia.suwayomi.ui

import koharia.suwayomi.SuwayomiSourceInfo

/** Language tag the server uses for its own local source entry. */
internal const val LOCAL_SOURCE_LANGUAGE = "localsourcelang"

internal data class SuwayomiSourceGroup(val label: String, val entries: List<SuwayomiSourceInfo>)

/**
 * Groups the server catalogue for the browse tab: pinned sources first, then one section per
 * source language with local sources last. Empty sections are dropped, so a filter that matches
 * nothing yields an empty list instead of empty headers.
 */
internal fun groupSuwayomiSources(
    sources: List<SuwayomiSourceInfo>,
    query: String,
    pinnedLabel: String,
    languageLabel: (String) -> String,
): List<SuwayomiSourceGroup> {
    val trimmed = query.trim()
    val filtered = if (trimmed.isEmpty()) {
        sources
    } else {
        sources.filter {
            it.name.contains(trimmed, ignoreCase = true) || it.lang.contains(trimmed, ignoreCase = true)
        }
    }
    val (pinned, rest) = filtered.partition { it.isPinned }
    return buildList {
        if (pinned.isNotEmpty()) add(SuwayomiSourceGroup(pinnedLabel, pinned.sortedBy { it.name.lowercase() }))
        rest.groupBy { it.lang }
            .entries
            .sortedWith(
                compareBy(
                    { if (it.key.equals(LOCAL_SOURCE_LANGUAGE, ignoreCase = true)) 1 else 0 },
                    { languageLabel(it.key).lowercase() },
                ),
            )
            .forEach { (lang, entries) ->
                add(
                    SuwayomiSourceGroup(
                        label = languageLabel(lang).ifBlank { lang },
                        entries = entries.sortedBy { it.name.lowercase() },
                    ),
                )
            }
    }
}

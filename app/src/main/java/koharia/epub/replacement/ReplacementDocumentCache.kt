package koharia.epub.replacement

import koharia.epub.isSameEpubResource

/** Keeps one visible resource pinned; scanning/search cannot retain the entire book. */
internal class ReplacementDocumentCache(
    private val maxEntries: Int = 4,
    private val maxWeight: Long = 8L * 1024 * 1024,
) {
    private data class Entry(val document: ReplacementDocument?, val weight: Long)
    private val entries = LinkedHashMap<String, Entry>(4, 0.75f, true)
    private var activeKey: String? = null
    private var active: Entry? = null

    @Synchronized
    fun activate(href: String) {
        val key = normalize(href)
        if (activeKey?.isSameEpubResource(key) == true) return
        val previous = active
        val previousKey = activeKey
        activeKey = key
        active = matchingKey(key)?.let(entries::remove)
        if (previous != null && previousKey != null) entries[previousKey] = previous
        trim()
    }

    @Synchronized
    fun contains(href: String): Boolean = entry(href) != null

    @Synchronized
    fun get(href: String): ReplacementDocument? = entry(href)?.document

    @Synchronized
    fun put(href: String, document: ReplacementDocument) {
        val key = normalize(href)
        // A no-match marker needs no DOM or text retention.
        val retained = document.takeIf { it.mapping.edits.isNotEmpty() }
        val entry = Entry(retained, retained?.estimatedWeight() ?: 0)
        matchingKey(key)?.let(entries::remove)
        if (activeKey?.isSameEpubResource(key) == true) active = entry else entries[key] = entry
        trim()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        active = null
        activeKey = null
    }

    @Synchronized
    internal fun size(): Int = entries.size + if (active != null) 1 else 0

    private fun entry(href: String): Entry? {
        val key = normalize(href)
        return if (activeKey?.isSameEpubResource(key) == true && active != null) {
            active
        } else {
            matchingKey(key)?.let(entries::get)
        }
    }

    private fun matchingKey(key: String): String? = if (entries.containsKey(key)) {
        key
    } else {
        entries.keys.singleOrNull { it.isSameEpubResource(key) }
    }

    private fun trim() {
        var weight = (active?.weight ?: 0) + entries.values.sumOf { it.weight }
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && (size() > maxEntries || weight > maxWeight)) {
            weight -= iterator.next().value.weight
            iterator.remove()
        }
        // A single visible resource can exceed the budget; all speculative entries are then evicted.
    }

    private fun normalize(href: String) = href.substringBefore('#').substringBefore('?').trimStart('/')

    private fun ReplacementDocument.estimatedWeight(): Long =
        2L * (html.length + mapping.original.length.toLong() + mapping.text.length) +
            nodes.sumOf { 2L * (it.selector.length + it.mapping.original.length.toLong() + it.mapping.text.length) } +
            256L * (originalDom.getAllElements().size + displayDom.getAllElements().size + nodes.size * 2L)
}

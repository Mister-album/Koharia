package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.migrate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Rewrites owned resource references, never arbitrary text, account IDs, or remote resource IDs. */
internal class BackupConnectionReferences(private val previousId: Long, private val restoredId: Long) {
    init {
        require(previousId > 0 && restoredId > 0)
    }

    fun url(value: String): String {
        for (provider in listOf("lanraragi", "kavita", "smanga", "suwayomi")) {
            val prefix = "/$provider/"
            if (value.startsWith(prefix)) {
                val owner = value.removePrefix(prefix).substringBefore('/').toLongOrNull()
                require(owner == previousId) { "Backup resource belongs to another connection" }
                return prefix + restoredId + value.removePrefix(prefix).substringAfter('/', "").let { "/$it" }
            }
        }
        val uri = runCatching { URI(value) }.getOrNull() ?: return value
        if (uri.scheme in setOf("koharia-local-v1", "koharia-local-v2", "koharia-incoming-v1")) {
            require(uri.rawAuthority == previousId.toString()) { "Backup local resource belongs to another connection" }
            return value.replaceRange(
                value.indexOf("://") + 3,
                value.indexOf("://") + 3 + previousId.toString().length,
                restoredId.toString(),
            )
        }
        if (uri.scheme == "content" && uri.authority?.endsWith(".library-storage") == true) {
            val prefix = uri.rawPath.substringBefore("/document/", "") + "/document/"
            require(uri.rawPath.startsWith(prefix)) { "Invalid storage document reference" }
            val document = URLDecoder.decode(uri.rawPath.removePrefix(prefix), Charsets.UTF_8.name())
            require(document.substringBefore('|').toLongOrNull() == previousId) {
                "Backup storage resource belongs to another connection"
            }
            val replacement = "$restoredId|${document.substringAfter('|')}"
            val encoded = URLEncoder.encode(replacement, Charsets.UTF_8.name()).replace("+", "%20")
            return "${uri.scheme}://${uri.rawAuthority}$prefix$encoded"
        }
        val http = value.toHttpUrlOrNull() ?: return value
        val builder = http.newBuilder()
        var changed = false
        if (http.host == "kavita.invalid") {
            val namespace = http.pathSegments.first()
            require(namespace.substringBefore('-').toLongOrNull() == previousId) {
                "Backup publication belongs to another connection"
            }
            builder.setPathSegment(0, "$restoredId-${namespace.substringAfter('-')}")
            changed = true
        }
        for ((key, separator) in listOf("kohariaScope" to '/', "koharia" to '-')) {
            val scope = http.queryParameter(key) ?: continue
            require(scope.substringBefore(separator).toLongOrNull() == previousId) {
                "Backup image belongs to another connection"
            }
            require(http.queryParameterValues(key).size == 1)
            builder.setQueryParameter(key, "$restoredId$separator${scope.substringAfter(separator)}")
            changed = true
        }
        return if (changed) builder.build().toString() else value
    }

    private fun locator(value: String): String {
        val parsed = json.parseToJsonElement(value) as? JsonObject ?: return value
        val href = parsed["href"] as? JsonPrimitive ?: return value
        if (!href.isString) return value
        val mapped = url(href.content)
        return if (mapped == href.content) value else JsonObject(parsed + ("href" to JsonPrimitive(mapped))).toString()
    }

    /** Called only on the decoded import before any preferences or database writes are published. */
    fun manga(manga: BackupManga) {
        require(manga.source == previousId)
        manga.url = url(manga.url)
        manga.thumbnailUrl = manga.thumbnailUrl?.let(::url)
        manga.chapters.forEach { it.url = url(it.url) }
        manga.history = manga.history.map { it.copy(url = url(it.url)) }
        manga.tracking = manga.tracking.map { it.copy(trackingUrl = url(it.trackingUrl)) }
        manga.epubProgress = manga.epubProgress.map {
            it.copy(
                chapterUrl = url(it.chapterUrl),
                bookUrl = it.bookUrl?.let(::url),
                locatorJson = locator(it.locatorJson),
            )
        }
        manga.epubBookmarks = manga.epubBookmarks.map {
            it.copy(chapterUrl = url(it.chapterUrl), locatorJson = locator(it.locatorJson))
        }
        manga.ttsProgress = manga.ttsProgress.map { it.copy(chapterUrl = url(it.chapterUrl)) }
        manga.lanraragiState = manga.lanraragiState.map { it.copy(chapterUrl = url(it.chapterUrl)) }
        manga.smangaState = manga.smangaState.map { it.copy(chapterUrl = url(it.chapterUrl)) }
        manga.kavitaState = manga.kavitaState.map { it.copy(chapterUrl = url(it.chapterUrl)) }
        manga.kavitaAnnotations = manga.kavitaAnnotations.map {
            val payload = json.parseToJsonElement(it.payload) as JsonObject
            val storedLocator = payload["locatorJson"] as? JsonPrimitive
            val mappedPayload = if (storedLocator?.isString == true) {
                JsonObject(payload + ("locatorJson" to JsonPrimitive(locator(storedLocator.content)))).toString()
            } else {
                it.payload
            }
            it.copy(chapterUrl = url(it.chapterUrl), payload = mappedPayload)
        }
        manga.suwayomiState = manga.suwayomiState.map { it.copy(chapterUrl = url(it.chapterUrl)) }
        manga.source = restoredId
    }

    fun preferences(source: BackupSourcePreferences): BackupSourcePreferences {
        require(source.sourceKey == "source_$previousId")
        return source.copy(
            sourceKey = "source_$restoredId",
            prefs = source.prefs.map { preference ->
                when (preference.key) {
                    "local_library_config" -> {
                        val config = json.decodeFromString<LocalLibraryConfig>(
                            (preference.value as StringPreferenceValue).value,
                        ).let { stored ->
                            if (stored.schemaVersion <
                                7
                            ) {
                                stored.migrate(previousId, inferConfiguredSetup = true)
                            } else {
                                stored
                            }
                        }
                        preference.copy(
                            value = StringPreferenceValue(
                                json.encodeToString(
                                    config.copy(
                                        roots = config.roots.map { it.copy(treeUri = url(it.treeUri)) },
                                        detachedRoots = config.detachedRoots.map { it.copy(treeUri = url(it.treeUri)) },
                                        managedBaseTreeUri = url(config.managedBaseTreeUri),
                                        treeUri = url(config.treeUri),
                                    ),
                                ),
                            ),
                        )
                    }
                    "local_custom_cover_urls" -> preference.copy(
                        value = StringSetPreferenceValue(
                            (preference.value as StringSetPreferenceValue).value.mapTo(linkedSetOf(), ::url),
                        ),
                    )
                    else -> preference
                }
            },
        )
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

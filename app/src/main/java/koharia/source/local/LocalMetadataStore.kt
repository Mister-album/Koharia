package koharia.source.local

import android.content.Context
import com.hippo.unifile.UniFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.ComicInfoPublishingStatus
import java.nio.charset.StandardCharsets

class LocalMetadataStore(
    private val context: Context,
    private val profileId: Long,
    private val json: Json,
    private val xml: XML,
) {
    private val preferences = LocalLibraryPreferences(profileId, json)

    fun save(
        itemKey: String,
        metadata: LocalMetadataOverride,
        itemDirectory: UniFile? = null,
        contentType: LocalLibraryContentType = LocalLibraryContentType.MIXED,
        adjacentFileStem: String? = null,
    ): Boolean {
        val value = metadata.copy(updatedAt = System.currentTimeMillis())
        preferences.setMetadataOverride(itemKey, value)
        return when (preferences.getConfig().metadataStorage) {
            LocalMetadataStorage.DATABASE -> true
            LocalMetadataStorage.ADJACENT_SIDECAR -> {
                itemDirectory?.let { writeAdjacent(it, value, contentType, adjacentFileStem) } ?: false
            }
            LocalMetadataStorage.UNIFIED_DIRECTORY -> {
                writeUnified(value, itemKey)
            }
        }
    }

    fun readManifest(): LocalLibraryManifest? {
        val config = preferences.getConfig()
        val candidates = buildList {
            preferences.managedBaseDirectory(context)?.let(::add)
            config.roots.distinctBy { it.treeUri }.mapNotNullTo(this) { root ->
                preferences.resolveRoot(context, root)
            }
        }
        return candidates.firstNotNullOfOrNull { root ->
            val metadataRoot = root.findFile(".koharia")
            val file = metadataRoot?.findFile("library.json") ?: root.findFile("library.json")
            file?.let {
                runCatching {
                    it.openInputStream().use { input ->
                        json.decodeFromString<LocalLibraryManifest>(
                            input.readBytes().toString(StandardCharsets.UTF_8),
                        )
                    }
                }.getOrNull()
            }
        }
    }

    private fun writeAdjacent(
        directory: UniFile,
        metadata: LocalMetadataOverride,
        contentType: LocalLibraryContentType,
        fileStem: String?,
    ): Boolean {
        return when (contentType) {
            LocalLibraryContentType.BOOKS -> writeAtomic(
                directory = directory,
                fileName = fileStem?.let { "$it.metadata.opf" } ?: "metadata.opf",
                content = opf(metadata),
            )
            else -> writeAtomic(
                directory = directory,
                fileName = fileStem?.let { "$it.ComicInfo.xml" } ?: "ComicInfo.xml",
                content = comicInfo(metadata),
            )
        }
    }

    private fun writeUnified(metadata: LocalMetadataOverride, itemKey: String): Boolean {
        val root = preferences.metadataBaseDirectory(context) ?: return false
        val metadataRoot = root.findFile(".koharia") ?: root.createDirectory(".koharia") ?: return false
        val directory = metadataRoot.findFile("metadata") ?: metadataRoot.createDirectory("metadata") ?: return false
        return writeAtomic(
            directory = directory,
            fileName = "$itemKey.json",
            content = json.encodeToString(metadata),
        )
    }

    private fun writeAtomic(directory: UniFile, fileName: String, content: String): Boolean {
        val temporary = directory.createFile(".$fileName.tmp") ?: return false
        return runCatching {
            temporary.openOutputStream().use { it.write(content.toByteArray(StandardCharsets.UTF_8)) }
            directory.findFile(fileName)?.delete()
            temporary.renameTo(fileName)
        }.getOrElse {
            temporary.delete()
            false
        }
    }

    private fun comicInfo(metadata: LocalMetadataOverride): String {
        val info = ComicInfo(
            title = metadata.title?.let(ComicInfo::Title),
            series = metadata.title?.let(ComicInfo::Series),
            number = null,
            summary = metadata.description?.let(ComicInfo::Summary),
            writer = metadata.author?.let(ComicInfo::Writer),
            penciller = metadata.artist?.let(ComicInfo::Penciller),
            inker = null,
            colorist = null,
            letterer = null,
            coverArtist = null,
            translator = null,
            genre = metadata.genres.takeIf(List<String>::isNotEmpty)?.joinToString(", ")?.let(ComicInfo::Genre),
            tags = null,
            web = null,
            publishingStatus = metadata.status?.let {
                ComicInfo.PublishingStatusTachiyomi(ComicInfoPublishingStatus.toComicInfoValue(it.toLong()))
            },
            categories = null,
            source = ComicInfo.SourceKoharia("Koharia"),
        )
        return xml.encodeToString(ComicInfo.serializer(), info)
    }

    private fun opf(metadata: LocalMetadataOverride): String {
        fun escape(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        val title = escape(metadata.title.orEmpty())
        val creator = escape(metadata.author.orEmpty())
        val description = escape(metadata.description.orEmpty())
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="book-id">$profileId-$title</dc:identifier>
                <dc:title>$title</dc:title>
                <dc:creator>$creator</dc:creator>
                <dc:description>$description</dc:description>
              </metadata>
            </package>
        """.trimIndent()
    }
}

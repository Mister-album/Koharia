package koharia.source.local

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nl.adaptivity.xmlutil.core.AndroidXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.ComicInfoPublishingStatus
import tachiyomi.core.metadata.comicinfo.copyFromComicInfo
import java.nio.charset.StandardCharsets
import java.util.UUID

class LocalMetadataStore(
    private val context: Context,
    private val profileId: Long,
    private val json: Json,
    private val xml: XML,
    private val preferences: LocalLibraryPreferences = LocalLibraryPreferences(profileId, json),
) {

    fun save(
        itemKey: String,
        metadata: LocalMetadataOverride,
        itemDirectory: UniFile? = null,
        contentType: LocalLibraryContentType = LocalLibraryContentType.MIXED,
        adjacentFileStem: String? = null,
        entryName: String? = null,
        role: LocalMetadataRole = LocalMetadataRole.INDIVIDUAL_FILE,
        overwriteExternal: Boolean = false,
    ): Boolean {
        if (!role.isMetadataEditable()) return false
        val value = metadata.copy(updatedAt = System.currentTimeMillis())
        if (preferences.getConfig().metadataStorage == LocalMetadataStorage.DATABASE) {
            preferences.setMetadataOverride(itemKey, value)
            return true
        }
        val (
            directory,
            name,
        ) = target(
            itemKey,
            itemDirectory,
            contentType,
            adjacentFileStem,
            entryName,
            role,
            true,
        )
            ?: return false
        val content = if (contentType == LocalLibraryContentType.BOOKS) opf(value) else comicInfo(value)
        val current = directory.findFile(name)?.openInputStream()?.use {
            it.readBytes().toString(StandardCharsets.UTF_8)
        }
        if (!overwriteExternal &&
            current != null &&
            current != content &&
            preferences.metadataRevision(itemKey) != metadataRevision(current)
        ) {
            throw LocalMetadataConflictException()
        }
        val written = writeAtomic(directory, name, content)
        if (written) {
            preferences.setMetadataOverride(itemKey, value)
            preferences.setMetadataRevision(itemKey, metadataRevision(content))
        }
        return written
    }

    private fun target(
        key: String,
        directory: UniFile?,
        type: LocalLibraryContentType,
        stem: String?,
        entryName: String?,
        role: LocalMetadataRole,
        create: Boolean,
    ): Pair<UniFile, String>? {
        if (role == LocalMetadataRole.FOLDER_CONTAINER || role == LocalMetadataRole.CHAPTER) return null
        val suffix = if (type == LocalLibraryContentType.BOOKS) "metadata.opf" else "ComicInfo.xml"
        val virtualStem = ".koharia-image-series"
        return when (preferences.getConfig().metadataStorage) {
            LocalMetadataStorage.DATABASE -> null
            LocalMetadataStorage.ADJACENT_SIDECAR -> directory?.let {
                it to when (role) {
                    LocalMetadataRole.FOLDER_IMAGE_SERIES -> "$virtualStem.$suffix"
                    else -> stem?.let { "$it.$suffix" } ?: suffix
                }
            }
            LocalMetadataStorage.UNIFIED_DIRECTORY -> standardDirectory(null, create)?.let { it to "$key.$suffix" }
            LocalMetadataStorage.FOLDER_DIRECTORY -> directory?.let { standardDirectory(it, create) }
                ?.let {
                    it to when (role) {
                        LocalMetadataRole.FOLDER_IMAGE_SERIES -> "$virtualStem.$suffix"
                        else -> entryName?.let { "$it.$suffix" } ?: suffix
                    }
                }
        }
    }

    internal fun externalMetadata(
        key: String,
        directory: UniFile?,
        type: LocalLibraryContentType,
        entryName: String?,
        role: LocalMetadataRole = LocalMetadataRole.INDIVIDUAL_FILE,
        observe: Boolean = false,
        acceptExternalChanges: Boolean = false,
    ): LocalMetadataOverride? {
        val file = existingMetadataFile(
            key,
            directory,
            type,
            entryName?.substringBeforeLast('.'),
            entryName,
            role,
        )
            ?: return null
        val content = file.openInputStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }
        val result = parseStandard(content, type) ?: return null
        if (acceptExternalChanges || (observe && preferences.metadataRevision(key) == null)) {
            preferences.setMetadataRevision(key, metadataRevision(content))
        }
        return result
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

    private fun standardDirectory(itemDirectory: UniFile?, create: Boolean = true): UniFile? {
        val root = itemDirectory ?: preferences.metadataBaseDirectory(context) ?: return null
        val metadataRoot = root.findFile(".koharia") ?: if (create) root.createDirectory(".koharia") else null
        return metadataRoot?.findFile("metadata") ?: if (create) metadataRoot?.createDirectory("metadata") else null
    }

    private fun writeStandard(
        directory: UniFile,
        key: String,
        value: LocalMetadataOverride,
        type: LocalLibraryContentType,
    ): Boolean {
        val name = standardName(key, type)
        val content = if (type == LocalLibraryContentType.BOOKS) opf(value) else comicInfo(value)
        return writeAtomic(directory, name, content)
    }

    private fun standardName(key: String, type: LocalLibraryContentType): String =
        if (type == LocalLibraryContentType.BOOKS) "$key.metadata.opf" else "$key.ComicInfo.xml"

    internal fun readAndMigrate(
        key: String,
        itemDirectory: UniFile?,
        type: LocalLibraryContentType,
        entryName: String? = null,
        role: LocalMetadataRole = LocalMetadataRole.INDIVIDUAL_FILE,
        legacyImageOwnership: Boolean = false,
    ): LocalMetadataOverride? {
        if (role == LocalMetadataRole.FOLDER_CONTAINER || role == LocalMetadataRole.CHAPTER) return null
        val config = preferences.getConfig()
        val standard = existingMetadataFile(
            key = key,
            directory = itemDirectory,
            type = type,
            stem = entryName?.substringBeforeLast('.'),
            entryName = entryName,
            role = role,
        )
        if (standard != null) return readStandard(standard, type)
        val alreadyStored = config.metadataStorage == LocalMetadataStorage.DATABASE &&
            preferences.getMetadataOverrides()[key] != null
        if (role == LocalMetadataRole.FOLDER_IMAGE_SERIES && legacyImageOwnership && itemDirectory != null &&
            !alreadyStored
        ) {
            legacyImageMetadata(itemDirectory)?.let { legacy ->
                val value = preferences.getMetadataOverrides()[key] ?: legacy
                if (save(key, value, itemDirectory, LocalLibraryContentType.COMICS, role = role)) return value
            }
        }
        val directory = standardDirectory(null, create = false)
        val legacy = directory?.findFile("$key.json") ?: return null
        val value = runCatching {
            legacy.openInputStream().use {
                json.decodeFromString<LocalMetadataOverride>(it.readBytes().toString(StandardCharsets.UTF_8))
            }
        }.getOrNull() ?: return null
        if (config.metadataStorage == LocalMetadataStorage.UNIFIED_DIRECTORY) {
            // Retain application-only fields and never overwrite a standard sidecar during migration.
            if (preferences.getMetadataOverrides()[key] == null) preferences.setMetadataOverride(key, value)
            runCatching {
                writeStandard(directory, key, preferences.getMetadataOverrides()[key] ?: value, type)
            }
        }
        return value
    }

    internal fun legacyImageMetadata(directory: UniFile): LocalMetadataOverride? {
        val candidates = listOfNotNull(
            directory.findFile("ComicInfo.xml"),
            standardDirectory(directory, create = false)?.findFile("ComicInfo.xml"),
        )
        return candidates.firstNotNullOfOrNull { readStandard(it, LocalLibraryContentType.COMICS) }
    }

    private fun existingMetadataFile(
        key: String,
        directory: UniFile?,
        type: LocalLibraryContentType,
        stem: String?,
        entryName: String?,
        role: LocalMetadataRole,
    ): UniFile? {
        val (parent, name) = target(key, directory, type, stem, entryName, role, false) ?: return null
        parent.findFile(name)?.let { return it }
        if (preferences.getConfig().metadataStorage != LocalMetadataStorage.FOLDER_DIRECTORY ||
            role != LocalMetadataRole.INDIVIDUAL_FILE
        ) {
            return null
        }
        val legacyStem = entryName?.substringBeforeLast('.', missingDelimiterValue = "")
            ?.takeIf(String::isNotBlank)
            ?: return null
        val suffix = if (type == LocalLibraryContentType.BOOKS) "metadata.opf" else "ComicInfo.xml"
        return parent.findFile("$legacyStem.$suffix")
    }

    internal fun readStandard(file: UniFile, type: LocalLibraryContentType): LocalMetadataOverride? = runCatching {
        parseStandard(file.openInputStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }, type)
    }.getOrNull()

    private fun parseStandard(content: String, type: LocalLibraryContentType): LocalMetadataOverride? = runCatching {
        if (type == LocalLibraryContentType.BOOKS) {
            val metadata = parseLocalOpfMetadata(Jsoup.parse(content, "", Parser.xmlParser()))
            LocalMetadataOverride(
                title = metadata.title,
                author = metadata.authors.joinToString(", ").takeIf {
                    it.isNotEmpty()
                },
                artist = metadata.contributors.joinToString(", ").takeIf {
                    it.isNotEmpty()
                },
                description = metadata.description,
                genres = metadata.subjects,
                source = "sidecar",
            )
        } else {
            val manga = SManga.create().apply { title = "" }
            AndroidXmlReader(content.byteInputStream(), StandardCharsets.UTF_8.name()).use { reader ->
                manga.copyFromComicInfo(xml.decodeFromReader<ComicInfo>(reader))
            }
            LocalMetadataOverride(
                title = manga.title.takeIf {
                    it.isNotEmpty()
                },
                author = manga.author,
                artist = manga.artist,
                description = manga.description,
                genres = manga.genre?.split(',')?.map(String::trim).orEmpty(),
                status = manga.status.takeIf { it != SManga.UNKNOWN },
                source = "sidecar",
            )
        }
    }.getOrNull()

    internal fun folderMarker(directory: UniFile, create: Boolean): LocalFolderMarker? {
        val metadata = directory.findFile(".koharia") ?: if (create) directory.createDirectory(".koharia") else null
        metadata ?: return null
        val existing = metadata.findFile("folder.json")
        if (existing != null) {
            return runCatching {
                existing.openInputStream().use {
                    json.decodeFromString<LocalFolderMarker>(it.readBytes().toString(StandardCharsets.UTF_8))
                }
                    .takeIf { it.version == 1 && runCatching { UUID.fromString(it.id) }.isSuccess }
            }.getOrNull()
        }
        if (!create) return null
        val marker = LocalFolderMarker()
        return marker.takeIf { writeAtomic(metadata, "folder.json", json.encodeToString(marker)) }
    }

    internal fun saveFolderMarker(directory: UniFile, marker: LocalFolderMarker): Boolean {
        val metadata = directory.findFile(".koharia") ?: directory.createDirectory(".koharia") ?: return false
        return writeAtomic(metadata, "folder.json", json.encodeToString(marker))
    }

    private fun writeAtomic(directory: UniFile, fileName: String, content: String): Boolean =
        writeRecoverableLocalFile(directory, fileName, content)

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

    internal fun opf(metadata: LocalMetadataOverride): String {
        fun escape(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        val title = escape(metadata.title.orEmpty())
        val creator = escape(metadata.author.orEmpty())
        val description = escape(metadata.description.orEmpty())
        val contributor = metadata.artist?.let { "<dc:contributor>${escape(it)}</dc:contributor>" }.orEmpty()
        val subjects = metadata.genres.joinToString("\n") { "<dc:subject>${escape(it)}</dc:subject>" }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="book-id">$profileId-$title</dc:identifier>
                <dc:title>$title</dc:title>
                <dc:creator>$creator</dc:creator>
                <dc:description>$description</dc:description>
                $contributor
                $subjects
              </metadata>
            </package>
        """.trimIndent()
    }
}

internal fun LocalMetadataOverride.applyTo(manga: SManga) {
    title?.let { manga.title = it }
    author?.let { manga.author = it }
    artist?.let { manga.artist = it }
    description?.let { manga.description = it }
    if (genres.isNotEmpty() || "genres" in lockedFields) manga.genre = genres.joinToString(", ")
    status?.let { manga.status = it }
}

internal fun writeRecoverableLocalFile(directory: UniFile, name: String, content: String): Boolean {
    val token = UUID.randomUUID().toString()
    val temporary = directory.createFile(".$name.$token.tmp") ?: return false
    var backup: UniFile? = null
    return runCatching {
        temporary.openOutputStream().use { it.write(content.toByteArray(StandardCharsets.UTF_8)) }
        check(temporary.openInputStream().use { it.readBytes().toString(StandardCharsets.UTF_8) } == content)
        directory.findFile(name)?.let { old ->
            check(old.renameTo(".$name.$token.bak"))
            backup = old
        }
        check(temporary.renameTo(name))
        check(
            directory.findFile(name)?.openInputStream()?.use {
                it.readBytes().toString(StandardCharsets.UTF_8)
            }
                == content,
        )
        true
    }.getOrElse {
        if (directory.findFile(name) == null) backup?.renameTo(name)
        temporary.takeIf { it.name?.endsWith(".tmp") == true }?.delete()
        false
    }
}

class LocalMetadataConflictException : java.io.IOException("External metadata changed")

internal fun metadataRevision(content: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(content.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

package koharia.importing

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import koharia.connection.ConnectionMediaImportItem
import koharia.core.archive.archiveReader
import koharia.core.archive.epubReader
import koharia.source.local.parseLocalOpfMetadata
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import nl.adaptivity.xmlutil.core.AndroidXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

internal object IncomingMediaMetadataScanner {

    suspend fun suggestedSeriesName(
        context: Context,
        items: List<ConnectionMediaImportItem>,
    ): String? = withIOContext {
        val xml = Injekt.get<XML>()
        val candidates = items.map { item ->
            try {
                readMetadata(context, item, xml)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logcat(LogPriority.WARN, error) { "Unable to read embedded metadata from incoming media" }
                null
            }
        }
        selectIncomingSeriesName(candidates)
    }

    private fun readMetadata(
        context: Context,
        item: ConnectionMediaImportItem,
        xml: XML,
    ): IncomingMediaMetadata? {
        if (item.extension !in METADATA_CONTAINER_EXTENSIONS) return null
        val uri = Uri.parse(item.uri)
        val directFile = UniFile.fromUri(context, uri)
            ?: return readFromTemporaryCopy(context, uri, item.extension, xml)
        return try {
            readMetadata(context, directFile, item.extension, xml)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            readFromTemporaryCopy(context, uri, item.extension, xml)
        }
    }

    private fun readFromTemporaryCopy(
        context: Context,
        uri: Uri,
        extension: String,
        xml: XML,
    ): IncomingMediaMetadata? {
        val cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
        val temporaryFile = File(cacheDirectory, "${UUID.randomUUID()}.$extension")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporaryFile.outputStream().use(input::copyTo)
            } ?: return null
            val copiedFile = UniFile.fromUri(context, Uri.fromFile(temporaryFile)) ?: return null
            readMetadata(
                context = context,
                file = copiedFile,
                extension = extension,
                xml = xml,
            )
        } finally {
            temporaryFile.delete()
        }
    }

    private fun readMetadata(
        context: Context,
        file: UniFile,
        extension: String,
        xml: XML,
    ): IncomingMediaMetadata? {
        return if (extension == "epub") {
            file.epubReader(context).use { epub ->
                val packageHref = epub.getPackageHref()
                val packageDocument = epub.getInputStream(packageHref)?.use { input ->
                    Jsoup.parse(
                        ByteArrayInputStream(input.readUpTo(MAX_METADATA_BYTES)),
                        StandardCharsets.UTF_8.name(),
                        "",
                        Parser.xmlParser(),
                    )
                } ?: return null
                val metadata = parseLocalOpfMetadata(packageDocument)
                IncomingMediaMetadata(series = metadata.series, title = metadata.title)
            }
        } else {
            file.archiveReader(context).use { reader ->
                val comicInfoEntry = reader.useEntries { entries ->
                    entries
                        .filter { entry ->
                            entry.isFile && entry.name
                                .replace('\\', '/')
                                .substringAfterLast('/')
                                .equals(COMIC_INFO_FILE, ignoreCase = true)
                        }
                        .minByOrNull { entry -> entry.name.count { it == '/' || it == '\\' } }
                } ?: return null
                val comicInfo = reader.getInputStream(comicInfoEntry.name)?.use { input ->
                    val bytes = input.readUpTo(MAX_METADATA_BYTES)
                    AndroidXmlReader(
                        ByteArrayInputStream(bytes),
                        StandardCharsets.UTF_8.name(),
                    ).use { xmlReader ->
                        xml.decodeFromReader<ComicInfo>(xmlReader)
                    }
                } ?: return null
                IncomingMediaMetadata(
                    series = comicInfo.series?.value,
                    title = comicInfo.title?.value,
                )
            }
        }
    }
}

internal data class IncomingMediaMetadata(
    val series: String? = null,
    val title: String? = null,
)

internal fun selectIncomingSeriesName(candidates: List<IncomingMediaMetadata?>): String? {
    if (candidates.isEmpty()) return null

    val series = candidates.mapNotNull { it?.series.validMetadataValue() }
    if (series.isNotEmpty()) {
        return series.singleNormalizedValue()
    }

    val titles = candidates.map { it?.title.validMetadataValue() }
    return if (titles.size == 1) {
        titles.single()
    } else if (titles.all { it != null }) {
        titles.filterNotNull().singleNormalizedValue()
    } else {
        null
    }
}

private fun List<String>.singleNormalizedValue(): String? {
    val distinct = distinctBy { it.lowercase(Locale.ROOT) }
    return distinct.singleOrNull()
}

private fun String?.validMetadataValue(): String? {
    val value = this
        ?.replace(Regex("[\\u0000-\\u001f]"), "")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null
    return value.takeUnless { it.lowercase(Locale.ROOT) in INVALID_METADATA_VALUES }
}

private fun InputStream.readUpTo(maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = maxBytes + 1
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        output.write(buffer, 0, read)
        remaining -= read
    }
    check(output.size() <= maxBytes) { "Embedded metadata exceeds the supported size" }
    return output.toByteArray()
}

private const val CACHE_DIRECTORY = "incoming-metadata"
private const val MAX_METADATA_BYTES = 2 * 1024 * 1024
private val METADATA_CONTAINER_EXTENSIONS = setOf("cbz", "zip", "cbr", "rar", "7z", "tar", "epub")
private val INVALID_METADATA_VALUES = setOf(
    "untitled",
    "unknown",
    "unknown title",
    "n/a",
    "none",
    "无标题",
    "未命名",
)

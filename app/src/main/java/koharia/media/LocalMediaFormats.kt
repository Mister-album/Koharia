package koharia.media

import java.util.Locale

/** The reader and import capabilities available to the local library. */
enum class LocalMediaKind {
    ARCHIVE,
    IMAGE,
    EPUB,
    PDF,
    TEXT,
    MOBI,
    DJVU,
}

enum class LocalMediaSupport {
    STABLE,
    EXPERIMENTAL,
    UNAVAILABLE,
}

data class LocalMediaFormat(
    val kind: LocalMediaKind,
    val extensions: Set<String>,
    val support: LocalMediaSupport,
    val mimeTypes: Set<String>,
) {
    fun matches(extension: String?): Boolean {
        return extension?.lowercase(Locale.ROOT) in extensions
    }
}

object LocalMediaFormats {
    val archives = LocalMediaFormat(
        kind = LocalMediaKind.ARCHIVE,
        extensions = setOf("cbz", "zip", "cbr", "rar", "7z", "cb7", "tar", "cbt"),
        support = LocalMediaSupport.STABLE,
        mimeTypes = setOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/vnd.comicbook+zip",
            "application/rar",
            "application/vnd.rar",
            "application/x-rar-compressed",
            "application/x-7z-compressed",
            "application/x-tar",
        ),
    )

    val images = LocalMediaFormat(
        kind = LocalMediaKind.IMAGE,
        extensions = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "heif", "heic", "jxl"),
        support = LocalMediaSupport.STABLE,
        mimeTypes = setOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "image/avif",
            "image/heif",
            "image/heic",
            "image/jxl",
        ),
    )

    val epub = LocalMediaFormat(
        kind = LocalMediaKind.EPUB,
        extensions = setOf("epub"),
        support = LocalMediaSupport.STABLE,
        mimeTypes = setOf("application/epub+zip"),
    )

    val pdf = LocalMediaFormat(
        kind = LocalMediaKind.PDF,
        extensions = setOf("pdf"),
        support = LocalMediaSupport.STABLE,
        mimeTypes = setOf("application/pdf"),
    )

    val text = LocalMediaFormat(
        kind = LocalMediaKind.TEXT,
        extensions = setOf("txt"),
        support = LocalMediaSupport.STABLE,
        mimeTypes = setOf("text/plain"),
    )

    /** PalmDOC and KF8 text are supported; DRM-protected files are intentionally rejected. */
    val mobi = LocalMediaFormat(
        kind = LocalMediaKind.MOBI,
        extensions = setOf("mobi", "prc", "azw", "azw3"),
        support = LocalMediaSupport.EXPERIMENTAL,
        mimeTypes = setOf(
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook",
            "application/x-palm-database",
        ),
    )

    /** Full JB2/IW44 DjVu decoding is provided by djvu-rs WASM through Android WebView/V8. */
    val djvu = LocalMediaFormat(
        kind = LocalMediaKind.DJVU,
        extensions = setOf("djvu", "djv"),
        support = LocalMediaSupport.STABLE,
        mimeTypes = setOf("image/vnd.djvu", "image/x-djvu"),
    )

    val all: List<LocalMediaFormat> = listOf(archives, images, epub, pdf, text, mobi, djvu)
    val available: List<LocalMediaFormat> = all.filter { it.support != LocalMediaSupport.UNAVAILABLE }
    val knownExtensions: Set<String> = all.flatMapTo(linkedSetOf()) { it.extensions }
    val allExtensions: Set<String> = available.flatMapTo(linkedSetOf()) { it.extensions }
    val bookExtensions: Set<String> = (setOf(epub, pdf, text, mobi, djvu))
        .flatMapTo(linkedSetOf()) { it.extensions }
    val reflowableBookExtensions: Set<String> = (setOf(epub, text, mobi))
        .flatMapTo(linkedSetOf()) { it.extensions }
    val comicExtensions: Set<String> = (allExtensions - bookExtensions) + pdf.extensions
    val allMimeTypes: Set<String> = available.flatMapTo(linkedSetOf()) { it.mimeTypes }

    fun find(extension: String?): LocalMediaFormat? {
        val normalized = extension?.lowercase(Locale.ROOT) ?: return null
        return all.firstOrNull { normalized in it.extensions }
    }

    fun extensionFromMimeType(mimeType: String?): String? {
        val normalized = mimeType?.lowercase(Locale.ROOT) ?: return null
        return available.firstNotNullOfOrNull { format ->
            if (normalized in format.mimeTypes) format.extensions.first() else null
        }
    }

    fun isArchive(extension: String?): Boolean = archives.matches(extension)
    fun isBook(extension: String?): Boolean = extension?.lowercase(Locale.ROOT) in bookExtensions
    fun isReflowableBook(extension: String?): Boolean =
        extension?.lowercase(Locale.ROOT) in reflowableBookExtensions
    fun isImage(extension: String?): Boolean = images.matches(extension)
}

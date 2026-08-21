package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import tachiyomi.core.common.storage.extension
import tachiyomi.source.local.io.Archive.isSupported as isArchiveSupported

sealed interface Format {
    data class Directory(val file: UniFile) : Format
    data class Archive(val file: UniFile) : Format
    data class Epub(val file: UniFile) : Format
    data class Image(val file: UniFile) : Format
    data class Text(val file: UniFile) : Format
    data class Mobi(val file: UniFile) : Format
    data class Djvu(val file: UniFile) : Format

    class UnknownFormatException : Exception()

    companion object {

        fun valueOf(file: UniFile) = when {
            file.isDirectory -> Directory(file)
            file.extension.equals("epub", true) -> Epub(file)
            file.extension.orEmpty().lowercase() in IMAGE_EXTENSIONS -> Image(file)
            file.extension.equals("txt", true) -> Text(file)
            file.extension.orEmpty().lowercase() in MOBI_EXTENSIONS -> Mobi(file)
            file.extension.orEmpty().lowercase() in DJVU_EXTENSIONS -> Djvu(file)
            isArchiveSupported(file) -> Archive(file)
            else -> throw UnknownFormatException()
        }

        private val MOBI_EXTENSIONS = setOf("mobi", "prc", "azw", "azw3")
        private val DJVU_EXTENSIONS = setOf("djvu", "djv")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "heif", "heic", "jxl")
    }
}

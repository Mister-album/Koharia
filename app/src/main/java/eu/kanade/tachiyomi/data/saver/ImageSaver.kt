package eu.kanade.tachiyomi.data.saver

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.contentValuesOf
import androidx.core.net.toUri
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import eu.kanade.tachiyomi.util.storage.getUriCompat
import logcat.LogPriority
import okio.IOException
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.Instant

class ImageSaver(
    val context: Context,
) {

    fun save(image: Image): Uri {
        val data = image.data
        val format = resolveFormat(image, data)
        val filename = DiskUtil.buildValidFilename("${image.name}.${format.extension}")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || image.location !is Location.Pictures) {
            return save(data(), image.location.directory(context), filename)
        }

        return saveApi29(image, format, filename, data)
    }

    private fun resolveFormat(image: Image, data: () -> InputStream): EncodedFormat {
        image.encodedFormat?.let { declared ->
            if (declared.mimeType == SVG_MIME_TYPE) {
                require(declared.extension.equals(SVG_EXTENSION, ignoreCase = true)) { "Invalid SVG extension" }
                require(data().use(::isSvg)) { "Not an SVG image" }
                return EncodedFormat(SVG_MIME_TYPE, SVG_EXTENSION)
            }
        }

        val type = ImageUtil.findImageType(data) ?: throw IllegalArgumentException("Not an image")
        return EncodedFormat(type.mime, type.extension)
    }

    private fun save(inputStream: InputStream, directory: File, filename: String): Uri {
        directory.mkdirs()

        val destFile = File(directory, filename)

        inputStream.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        DiskUtil.scanMedia(context, destFile.toUri())

        return destFile.getUriCompat(context)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveApi29(
        image: Image,
        format: EncodedFormat,
        filename: String,
        data: () -> InputStream,
    ): Uri {
        val useImageCollection = format.mimeType != SVG_MIME_TYPE

        val pictureDir = if (useImageCollection) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val imageLocation = (image.location as Location.Pictures).relativePath
        val relativePath = listOf(
            Environment.DIRECTORY_PICTURES,
            context.stringResource(MR.strings.app_name),
            imageLocation,
        ).joinToString(File.separator)

        val contentValues = contentValuesOf(
            MediaStore.MediaColumns.RELATIVE_PATH to relativePath,
            MediaStore.MediaColumns.DISPLAY_NAME to filename,
            MediaStore.MediaColumns.MIME_TYPE to format.mimeType,
            MediaStore.MediaColumns.DATE_MODIFIED to Instant.now().epochSecond,
        )

        val picture = findUriOrDefault(pictureDir, relativePath, filename) {
            context.contentResolver.insert(
                pictureDir,
                contentValues,
            ) ?: throw IOException(context.stringResource(MR.strings.error_saving_picture))
        }

        try {
            data().use { input ->
                context.contentResolver.openOutputStream(picture, "w").use { output ->
                    input.copyTo(output!!)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            throw IOException(context.stringResource(MR.strings.error_saving_picture))
        }

        DiskUtil.scanMedia(context, picture)

        return picture
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findUriOrDefault(collection: Uri, path: String, filename: String, default: () -> Uri): Uri {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )

        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"

        // Need to make sure it ends with the separator
        val normalizedPath = "${path.removeSuffix(File.separator)}${File.separator}"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            arrayOf(normalizedPath, filename),
            null,
        ).use { cursor ->
            if (cursor != null && cursor.count >= 1) {
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    return ContentUris.withAppendedId(collection, id)
                }
            }
        }

        return default()
    }

    private fun isSvg(input: InputStream): Boolean {
        val prefix = input.bufferedReader().use { reader ->
            val buffer = CharArray(SVG_PREFIX_CHARS)
            val length = reader.read(buffer)
            if (length <= 0) "" else String(buffer, 0, length)
        }
        return SVG_ROOT_PATTERN.containsMatchIn(prefix.trimStart('\uFEFF'))
    }
}

sealed class Image(
    open val name: String,
    open val location: Location,
    open val encodedFormat: EncodedFormat? = null,
) {
    data class Cover(
        val bitmap: Bitmap,
        override val name: String,
        override val location: Location,
    ) : Image(name, location)

    data class Page(
        val inputStream: () -> InputStream,
        override val name: String,
        override val location: Location,
        override val encodedFormat: EncodedFormat? = null,
    ) : Image(name, location, encodedFormat)

    val data: () -> InputStream
        get() {
            return when (this) {
                is Cover -> {
                    {
                        val baos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos)
                        ByteArrayInputStream(baos.toByteArray())
                    }
                }
                is Page -> inputStream
            }
        }
}

sealed interface Location {
    @ConsistentCopyVisibility
    data class Pictures private constructor(val relativePath: String) : Location {
        companion object {
            fun create(relativePath: String = ""): Pictures {
                return Pictures(relativePath)
            }
        }
    }

    data object Cache : Location

    data object EpubShareCache : Location

    fun directory(context: Context): File {
        return when (this) {
            Cache -> context.cacheImageDir
            EpubShareCache -> File(context.cacheImageDir, "epub_share")
            is Pictures -> {
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    context.stringResource(MR.strings.app_name),
                )
                if (relativePath.isNotEmpty()) {
                    return File(
                        file,
                        relativePath,
                    )
                }
                file
            }
        }
    }
}

data class EncodedFormat(
    val mimeType: String,
    val extension: String,
)

private const val SVG_MIME_TYPE = "image/svg+xml"
private const val SVG_EXTENSION = "svg"
private const val SVG_PREFIX_CHARS = 16 * 1024
private val SVG_ROOT_PATTERN = Regex("""<svg(?:\s|>)""", RegexOption.IGNORE_CASE)

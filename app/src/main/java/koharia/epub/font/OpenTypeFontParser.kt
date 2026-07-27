package koharia.epub.font

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log2

internal class OpenTypeFontParser {

    fun parse(file: File, preferredLocale: Locale = Locale.getDefault()): ParsedOpenTypeFile {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= SFNT_HEADER_SIZE)
            val signature = input.readTag(0)
            val faceOffsets = if (signature == TAG_TTCF) {
                val version = input.readUInt32(4)
                require(version == TTC_VERSION_1 || version == TTC_VERSION_2)
                val faceCount = input.readUInt32(8).toInt()
                require(faceCount in 1..MAX_FACE_COUNT)
                requireRange(12L, faceCount.toLong() * 4L, input.length())
                List(faceCount) { index -> input.readUInt32(12L + index * 4L) }
            } else {
                listOf(0L)
            }
            val faces = faceOffsets.mapIndexed { index, offset ->
                parseFace(input, index, offset, preferredLocale)
            }
            return ParsedOpenTypeFile(
                isCollection = signature == TAG_TTCF,
                faces = faces,
            )
        }
    }

    fun extractFace(source: File, faceIndex: Int, destination: File) {
        RandomAccessFile(source, "r").use { input ->
            val parsed = parse(source)
            val face = parsed.faces.getOrNull(faceIndex) ?: error("Font face not found")
            val tables = face.tables.sortedBy { it.tag }
            require(tables.isNotEmpty())

            destination.parentFile?.mkdirs()
            RandomAccessFile(destination, "rw").use { output ->
                output.setLength(0)
                val numTables = tables.size
                val maxPower = Integer.highestOneBit(numTables)
                val searchRange = maxPower * 16
                val entrySelector = floor(log2(maxPower.toDouble())).toInt()
                val rangeShift = numTables * 16 - searchRange
                output.writeTag(face.sfntSignature)
                output.writeShort(numTables)
                output.writeShort(searchRange)
                output.writeShort(entrySelector)
                output.writeShort(rangeShift)
                repeat(numTables) { repeat(TABLE_RECORD_SIZE.toInt()) { output.write(0) } }

                val written = mutableListOf<WrittenTable>()
                var headOffset: Long? = null
                tables.forEach { table ->
                    output.align4()
                    val destinationOffset = output.filePointer
                    val checksum = copyTable(
                        input = input,
                        output = output,
                        table = table,
                        clearChecksumAdjustment = table.tag == TAG_HEAD,
                    )
                    output.align4()
                    if (table.tag == TAG_HEAD) headOffset = destinationOffset
                    written += WrittenTable(
                        table = table,
                        checksum = checksum,
                        destinationOffset = destinationOffset,
                    )
                }

                written.forEachIndexed { index, table ->
                    output.seek(SFNT_HEADER_SIZE + index.toLong() * TABLE_RECORD_SIZE)
                    output.writeTag(table.table.tag)
                    output.writeInt(table.checksum.toInt())
                    output.writeInt(table.destinationOffset.toInt())
                    output.writeInt(table.table.length.toInt())
                }

                val adjustmentOffset = headOffset?.plus(HEAD_CHECKSUM_ADJUSTMENT_OFFSET)
                if (adjustmentOffset != null) {
                    output.seek(adjustmentOffset)
                    output.writeInt(0)
                    val checksum = checksum(output)
                    val adjustment = CHECKSUM_MAGIC - checksum
                    output.seek(adjustmentOffset)
                    output.writeInt(adjustment.toInt())
                }
            }
        }
    }

    private fun parseFace(
        input: RandomAccessFile,
        index: Int,
        offset: Long,
        preferredLocale: Locale,
    ): ParsedOpenTypeFace {
        requireRange(offset, SFNT_HEADER_SIZE, input.length())
        val signature = input.readTag(offset)
        require(signature == TAG_TRUE_TYPE || signature == TAG_OTTO || signature == TAG_TRUE || signature == TAG_TYP1)
        val tableCount = input.readUInt16(offset + 4)
        require(tableCount in 1..MAX_TABLE_COUNT)
        requireRange(offset + SFNT_HEADER_SIZE, tableCount.toLong() * TABLE_RECORD_SIZE, input.length())
        val tables = List(tableCount) { tableIndex ->
            val recordOffset = offset + SFNT_HEADER_SIZE + tableIndex * TABLE_RECORD_SIZE
            val tableOffset = input.readUInt32(recordOffset + 8)
            val tableLength = input.readUInt32(recordOffset + 12)
            requireRange(tableOffset, tableLength, input.length())
            OpenTypeTable(
                tag = input.readTag(recordOffset),
                offset = tableOffset,
                length = tableLength,
            )
        }
        val tablesByTag = tables.associateBy { it.tag }
        require(tablesByTag.size == tables.size)
        val names = tablesByTag[TAG_NAME]?.let { readNames(input, it) }.orEmpty()
        val familyName = names.preferred(NAME_TYPOGRAPHIC_FAMILY)
            ?: names.preferred(NAME_FAMILY)
            ?: names.preferred(NAME_FULL)
            ?: "Font ${index + 1}"
        val localizedFamilyName = names.localized(NAME_TYPOGRAPHIC_FAMILY, preferredLocale)
            ?: names.localized(NAME_FAMILY, preferredLocale)
            ?: names.localized(NAME_FULL, preferredLocale)
        val subfamily = names.preferred(NAME_TYPOGRAPHIC_SUBFAMILY)
            ?: names.preferred(NAME_SUBFAMILY)
                .orEmpty()
        val manufacturer = names.preferred(NAME_MANUFACTURER)
        val postScriptName = names.preferred(NAME_POSTSCRIPT)
        val style = readStyle(input, tablesByTag, subfamily)
        val weightRange = readVariableWeightRange(input, tablesByTag[TAG_FVAR])
        return ParsedOpenTypeFace(
            index = index,
            familyName = familyName.trim().ifBlank { "Font ${index + 1}" },
            localizedFamilyName = localizedFamilyName
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals(familyName, ignoreCase = true) },
            manufacturer = manufacturer?.trim()?.takeIf { it.isNotBlank() },
            postScriptName = postScriptName?.trim()?.takeIf { it.isNotBlank() },
            weight = style.first,
            italic = style.second,
            minWeight = weightRange?.first ?: style.first,
            maxWeight = weightRange?.last ?: style.first,
            sfntSignature = signature,
            sfntFlavor = if (signature == TAG_OTTO) {
                EpubFontFaceDescriptor.SFNT_CFF
            } else {
                EpubFontFaceDescriptor.SFNT_TRUE_TYPE
            },
            tables = tables,
        )
    }

    private fun readNames(input: RandomAccessFile, table: OpenTypeTable): List<OpenTypeName> {
        if (table.length < 6) return emptyList()
        val count = input.readUInt16(table.offset + 2).coerceAtMost(MAX_NAME_RECORD_COUNT)
        val stringOffset = input.readUInt16(table.offset + 4)
        if (6L + count.toLong() * 12L > table.length) return emptyList()
        return buildList {
            repeat(count) { index ->
                val recordOffset = table.offset + 6L + index * 12L
                val platformId = input.readUInt16(recordOffset)
                val encodingId = input.readUInt16(recordOffset + 2)
                val languageId = input.readUInt16(recordOffset + 4)
                val nameId = input.readUInt16(recordOffset + 6)
                val length = input.readUInt16(recordOffset + 8)
                val offset = input.readUInt16(recordOffset + 10)
                val absoluteOffset = table.offset + stringOffset + offset
                if (!isRangeValid(absoluteOffset, length.toLong(), input.length())) return@repeat
                val bytes = ByteArray(length)
                input.seek(absoluteOffset)
                input.readFully(bytes)
                val value = decodeName(bytes, platformId, encodingId)
                    .repairMisencodedChineseName(platformId, languageId)
                    .trim { it <= ' ' || it == '\u0000' }
                if (value.isNotBlank()) {
                    add(OpenTypeName(nameId, platformId, languageId, value))
                }
            }
        }
    }

    private fun decodeName(bytes: ByteArray, platformId: Int, encodingId: Int): String {
        return when (platformId) {
            0, 3 -> bytes.toString(Charsets.UTF_16BE)
            1 -> bytes.toString(
                runCatching {
                    Charset.forName(
                        when (encodingId) {
                            MAC_ENCODING_JAPANESE -> "Shift_JIS"
                            MAC_ENCODING_TRADITIONAL_CHINESE -> "Big5"
                            MAC_ENCODING_KOREAN -> "EUC-KR"
                            MAC_ENCODING_SIMPLIFIED_CHINESE -> "GB18030"
                            else -> "x-MacRoman"
                        },
                    )
                }.getOrDefault(Charsets.ISO_8859_1),
            )
            else -> if (encodingId == 1 || encodingId == 10) {
                bytes.toString(Charsets.UTF_16BE)
            } else {
                bytes.toString(Charsets.ISO_8859_1)
            }
        }
    }

    private fun String.repairMisencodedChineseName(platformId: Int, languageId: Int): String {
        if (platformId != 3 || any { it.code > 0xFF } || none { it.code in 0x80..0xFF }) return this
        val charset = when (languageId) {
            in WINDOWS_SIMPLIFIED_CHINESE_LANGUAGES -> Charset.forName("GB18030")
            in WINDOWS_TRADITIONAL_CHINESE_LANGUAGES -> Charset.forName("Big5")
            else -> return this
        }
        val repaired = runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(toByteArray(Charsets.ISO_8859_1)))
                .toString()
        }.getOrNull() ?: return this
        return repaired.takeIf { value -> value.any { it.isCjk() } } ?: this
    }

    private fun Char.isCjk(): Boolean {
        return this in '\u3400'..'\u4DBF' || this in '\u4E00'..'\u9FFF' || this in '\uF900'..'\uFAFF'
    }

    private fun List<OpenTypeName>.preferred(nameId: Int): String? {
        return filter { it.nameId == nameId }
            .maxByOrNull { name ->
                when {
                    name.platformId == 3 && name.languageId == WINDOWS_ENGLISH_LANGUAGE -> 4
                    name.platformId == 0 -> 3
                    name.platformId == 3 -> 2
                    else -> 1
                }
            }
            ?.value
    }

    private fun List<OpenTypeName>.localized(nameId: Int, locale: Locale): String? {
        val windowsLanguageIds = windowsLanguageIds(locale)
        val macLanguageIds = macLanguageIds(locale)
        return filter { name ->
            name.nameId == nameId &&
                (
                    (name.platformId == 3 && name.languageId in windowsLanguageIds) ||
                        (name.platformId == 1 && name.languageId in macLanguageIds)
                    )
        }.maxByOrNull { name ->
            when (name.platformId) {
                3 -> windowsLanguageIds.size - windowsLanguageIds.indexOf(name.languageId)
                1 -> macLanguageIds.size - macLanguageIds.indexOf(name.languageId)
                else -> 0
            }
        }?.value
    }

    private fun windowsLanguageIds(locale: Locale): List<Int> = when (locale.language) {
        "zh" -> if (locale.script.equals("Hant", ignoreCase = true) ||
            locale.country in setOf("TW", "HK", "MO")
        ) {
            WINDOWS_TRADITIONAL_CHINESE_LANGUAGES
        } else {
            WINDOWS_SIMPLIFIED_CHINESE_LANGUAGES
        }
        "ja" -> listOf(0x0411)
        "ko" -> listOf(0x0412)
        "en" -> listOf(WINDOWS_ENGLISH_LANGUAGE)
        else -> emptyList()
    }

    private fun macLanguageIds(locale: Locale): List<Int> = when (locale.language) {
        "zh" -> if (locale.script.equals("Hant", ignoreCase = true) ||
            locale.country in setOf("TW", "HK", "MO")
        ) {
            listOf(MAC_LANGUAGE_TRADITIONAL_CHINESE)
        } else {
            listOf(MAC_LANGUAGE_SIMPLIFIED_CHINESE)
        }
        "ja" -> listOf(MAC_LANGUAGE_JAPANESE)
        "ko" -> listOf(MAC_LANGUAGE_KOREAN)
        "en" -> listOf(MAC_LANGUAGE_ENGLISH)
        else -> emptyList()
    }

    private fun readStyle(
        input: RandomAccessFile,
        tables: Map<String, OpenTypeTable>,
        subfamily: String,
    ): Pair<Int, Boolean> {
        var weight = subfamilyWeight(subfamily)
        var italic = subfamily.contains("italic", ignoreCase = true) ||
            subfamily.contains("oblique", ignoreCase = true)
        tables[TAG_OS2]?.let { table ->
            if (table.length >= 8) {
                weight = input.readUInt16(table.offset + 4).coerceIn(1, 1000)
            }
            if (table.length >= 64) {
                val selection = input.readUInt16(table.offset + 62)
                italic = italic || selection and 0x01 != 0
            }
        }
        tables[TAG_HEAD]?.let { table ->
            if (table.length >= 46) {
                val macStyle = input.readUInt16(table.offset + 44)
                italic = italic || macStyle and 0x02 != 0
                if (weight == 400 && macStyle and 0x01 != 0) weight = 700
            }
        }
        tables[TAG_POST]?.let { table ->
            if (table.length >= 8) {
                italic = italic || input.readFixed16Dot16(table.offset + 4) != 0.0
            }
        }
        return weight to italic
    }

    private fun subfamilyWeight(value: String): Int {
        return when {
            value.contains("thin", true) -> 100
            value.contains("extra light", true) || value.contains("ultra light", true) -> 200
            value.contains("light", true) -> 300
            value.contains("medium", true) -> 500
            value.contains("semi bold", true) || value.contains("demi bold", true) -> 600
            value.contains("extra bold", true) || value.contains("ultra bold", true) -> 800
            value.contains("black", true) || value.contains("heavy", true) -> 900
            value.contains("bold", true) -> 700
            else -> 400
        }
    }

    private fun readVariableWeightRange(input: RandomAccessFile, table: OpenTypeTable?): IntRange? {
        table ?: return null
        if (table.length < 16) return null
        val axesOffset = input.readUInt16(table.offset + 4)
        val axisCount = input.readUInt16(table.offset + 8).coerceAtMost(MAX_AXIS_COUNT)
        val axisSize = input.readUInt16(table.offset + 10)
        if (axisSize < 20) return null
        repeat(axisCount) { index ->
            val offset = table.offset + axesOffset + index.toLong() * axisSize
            if (!isRangeValid(offset, axisSize.toLong(), input.length())) return null
            if (input.readTag(offset) == TAG_WEIGHT_AXIS) {
                val min = input.readFixed16Dot16(offset + 4).toInt().coerceIn(1, 1000)
                val max = input.readFixed16Dot16(offset + 12).toInt().coerceIn(1, 1000)
                return min.coerceAtMost(max)..max.coerceAtLeast(min)
            }
        }
        return null
    }

    private fun copyTable(
        input: RandomAccessFile,
        output: RandomAccessFile,
        table: OpenTypeTable,
        clearChecksumAdjustment: Boolean,
    ): Long {
        input.seek(table.offset)
        var remaining = table.length
        var tablePosition = 0L
        var checksum = 0L
        val word = ByteArray(4)
        while (remaining > 0) {
            word.fill(0)
            val size = minOf(4L, remaining).toInt()
            input.readFully(word, 0, size)
            if (clearChecksumAdjustment) {
                repeat(size) { index ->
                    val absolute = tablePosition + index
                    if (absolute in HEAD_CHECKSUM_ADJUSTMENT_OFFSET until HEAD_CHECKSUM_ADJUSTMENT_OFFSET + 4) {
                        word[index] = 0
                    }
                }
            }
            output.write(word, 0, size)
            checksum =
                (checksum + ByteBuffer.wrap(word).order(ByteOrder.BIG_ENDIAN).int.toUInt().toLong()) and UINT32_MASK
            tablePosition += size
            remaining -= size
        }
        return checksum
    }

    private fun checksum(file: RandomAccessFile): Long {
        file.seek(0)
        var remaining = file.length()
        var result = 0L
        val word = ByteArray(4)
        while (remaining > 0) {
            word.fill(0)
            val size = minOf(4L, remaining).toInt()
            file.readFully(word, 0, size)
            result = (result + ByteBuffer.wrap(word).order(ByteOrder.BIG_ENDIAN).int.toUInt().toLong()) and UINT32_MASK
            remaining -= size
        }
        return result
    }

    private fun RandomAccessFile.readTag(offset: Long): String {
        requireRange(offset, 4, length())
        seek(offset)
        return ByteArray(4).also(::readFully).toString(Charsets.ISO_8859_1)
    }

    private fun RandomAccessFile.writeTag(tag: String) {
        require(tag.length == 4)
        write(tag.toByteArray(Charsets.ISO_8859_1))
    }

    private fun RandomAccessFile.readUInt16(offset: Long): Int {
        requireRange(offset, 2, length())
        seek(offset)
        return readUnsignedShort()
    }

    private fun RandomAccessFile.readUInt32(offset: Long): Long {
        requireRange(offset, 4, length())
        seek(offset)
        return readInt().toUInt().toLong()
    }

    private fun RandomAccessFile.readFixed16Dot16(offset: Long): Double {
        requireRange(offset, 4, length())
        seek(offset)
        return readInt() / 65536.0
    }

    private fun RandomAccessFile.align4() {
        while (filePointer % 4L != 0L) write(0)
    }

    private fun requireRange(offset: Long, size: Long, fileSize: Long) {
        require(isRangeValid(offset, size, fileSize))
    }

    private fun isRangeValid(offset: Long, size: Long, fileSize: Long): Boolean {
        return offset >= 0 && size >= 0 && offset <= fileSize && size <= fileSize - offset
    }

    private data class OpenTypeName(
        val nameId: Int,
        val platformId: Int,
        val languageId: Int,
        val value: String,
    )

    private data class WrittenTable(
        val table: OpenTypeTable,
        val checksum: Long,
        val destinationOffset: Long,
    )

    companion object {
        private const val TAG_TTCF = "ttcf"
        private const val TAG_TRUE_TYPE = "\u0000\u0001\u0000\u0000"
        private const val TAG_OTTO = "OTTO"
        private const val TAG_TRUE = "true"
        private const val TAG_TYP1 = "typ1"
        private const val TAG_NAME = "name"
        private const val TAG_OS2 = "OS/2"
        private const val TAG_HEAD = "head"
        private const val TAG_POST = "post"
        private const val TAG_FVAR = "fvar"
        private const val TAG_WEIGHT_AXIS = "wght"
        private const val NAME_FAMILY = 1
        private const val NAME_SUBFAMILY = 2
        private const val NAME_FULL = 4
        private const val NAME_POSTSCRIPT = 6
        private const val NAME_MANUFACTURER = 8
        private const val NAME_TYPOGRAPHIC_FAMILY = 16
        private const val NAME_TYPOGRAPHIC_SUBFAMILY = 17
        private const val WINDOWS_ENGLISH_LANGUAGE = 0x0409
        private val WINDOWS_SIMPLIFIED_CHINESE_LANGUAGES = listOf(0x0804, 0x1004)
        private val WINDOWS_TRADITIONAL_CHINESE_LANGUAGES = listOf(0x0404, 0x0C04, 0x1404)
        private const val MAC_ENCODING_JAPANESE = 1
        private const val MAC_ENCODING_TRADITIONAL_CHINESE = 2
        private const val MAC_ENCODING_KOREAN = 3
        private const val MAC_ENCODING_SIMPLIFIED_CHINESE = 25
        private const val MAC_LANGUAGE_ENGLISH = 0
        private const val MAC_LANGUAGE_JAPANESE = 11
        private const val MAC_LANGUAGE_TRADITIONAL_CHINESE = 19
        private const val MAC_LANGUAGE_KOREAN = 23
        private const val MAC_LANGUAGE_SIMPLIFIED_CHINESE = 33
        private const val MAX_FACE_COUNT = 128
        private const val MAX_TABLE_COUNT = 256
        private const val MAX_NAME_RECORD_COUNT = 512
        private const val MAX_AXIS_COUNT = 32
        private const val TTC_VERSION_1 = 0x0001_0000L
        private const val TTC_VERSION_2 = 0x0002_0000L
        private const val SFNT_HEADER_SIZE = 12L
        private const val TABLE_RECORD_SIZE = 16L
        private const val HEAD_CHECKSUM_ADJUSTMENT_OFFSET = 8L
        private const val UINT32_MASK = 0xFFFF_FFFFL
        private const val CHECKSUM_MAGIC = 0xB1B0_AFBAL
    }
}

internal data class ParsedOpenTypeFile(
    val isCollection: Boolean,
    val faces: List<ParsedOpenTypeFace>,
)

internal data class ParsedOpenTypeFace(
    val index: Int,
    val familyName: String,
    val localizedFamilyName: String?,
    val manufacturer: String?,
    val postScriptName: String?,
    val weight: Int,
    val italic: Boolean,
    val minWeight: Int,
    val maxWeight: Int,
    val sfntSignature: String,
    val sfntFlavor: String,
    internal val tables: List<OpenTypeTable>,
)

internal data class OpenTypeTable(
    val tag: String,
    val offset: Long,
    val length: Long,
)

package koharia.epub.font

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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
            val nameDecodeBudget = NameDecodeBudget(MAX_TOTAL_NAME_BYTES)
            val faces = faceOffsets.mapIndexed { index, offset ->
                parseFace(input, index, offset, preferredLocale, nameDecodeBudget)
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
            val compatibilityCmap = tables.firstOrNull { it.tag == TAG_CMAP }
                ?.let { buildUnicodeCompatibilityCmap(input, it) }

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
                    val replacement = compatibilityCmap.takeIf { table.tag == TAG_CMAP }
                    val writtenTable = replacement?.let { table.copy(offset = 0L, length = it.size.toLong()) } ?: table
                    val checksum = if (replacement != null) {
                        output.write(replacement)
                        checksumWords(replacement, replacement.size, 0L)
                    } else {
                        copyTable(
                            input = input,
                            output = output,
                            table = table,
                            clearChecksumAdjustment = table.tag == TAG_HEAD,
                        )
                    }
                    output.align4()
                    if (table.tag == TAG_HEAD) headOffset = destinationOffset
                    written += WrittenTable(
                        table = writtenTable,
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

    private fun buildUnicodeCompatibilityCmap(input: RandomAccessFile, table: OpenTypeTable): ByteArray? {
        if (table.length !in 4..MAX_CMAP_BYTES) return null
        val bytes = ByteArray(table.length.toInt())
        input.seek(table.offset)
        input.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val recordCount = buffer.uInt16(2)
        if (recordCount !in 1..MAX_CMAP_RECORDS || 4L + recordCount.toLong() * 8L > bytes.size) return null
        val records = buildList {
            for (index in 0 until recordCount) {
                val recordOffset = 4 + index * 8
                val platformId = buffer.uInt16(recordOffset)
                val encodingId = buffer.uInt16(recordOffset + 2)
                val rawSubtableOffset = buffer.uInt32(recordOffset + 4) ?: continue
                if (rawSubtableOffset > Int.MAX_VALUE) continue
                val subtableOffset = rawSubtableOffset.toInt()
                val format = buffer.uInt16OrNull(subtableOffset) ?: continue
                add(CmapEncodingRecord(platformId, encodingId, subtableOffset, format))
            }
        }
        if (records.any { it.isChromiumUnicodeCmap() }) return null
        val legacy = records
            .asSequence()
            .filter { it.format == CMAP_FORMAT_HIGH_BYTE_MAPPING }
            .mapNotNull { record -> legacyCharset(record)?.let { charset -> record to charset } }
            .sortedByDescending { (record) -> if (record.platformId == WINDOWS_PLATFORM_ID) 1 else 0 }
            .firstOrNull()
            ?: return null
        val encodedMappings = decodeFormat2(buffer, legacy.first.subtableOffset) ?: return null
        val unicodeMappings = linkedMapOf<Int, Int>()
        encodedMappings.forEach { (encodedCode, glyphId) ->
            decodeLegacyCodePoint(encodedCode, legacy.second)?.let { codePoint ->
                if (codePoint !in unicodeMappings) unicodeMappings[codePoint] = glyphId
            }
        }
        if (unicodeMappings.isEmpty()) return null
        return buildFormat12Cmap(unicodeMappings)
    }

    private fun decodeFormat2(buffer: ByteBuffer, subtableOffset: Int): Map<Int, Int>? {
        if (buffer.uInt16OrNull(subtableOffset) != CMAP_FORMAT_HIGH_BYTE_MAPPING) return null
        val length = buffer.uInt16OrNull(subtableOffset + 2) ?: return null
        val subtableEnd = subtableOffset.toLong() + length
        if (length < CMAP_FORMAT_2_MIN_BYTES || subtableEnd > buffer.limit()) return null
        val keysOffset = subtableOffset + 6
        val keys = IntArray(256) { index -> buffer.uInt16(keysOffset + index * 2) }
        if (keys.any { it % CMAP_FORMAT_2_SUBHEADER_BYTES != 0 }) return null
        val subheaderCount = keys.maxOrNull()!! / CMAP_FORMAT_2_SUBHEADER_BYTES + 1
        val subheadersOffset = keysOffset + 512
        if (subheadersOffset.toLong() + subheaderCount.toLong() * CMAP_FORMAT_2_SUBHEADER_BYTES > subtableEnd) {
            return null
        }
        return buildMap {
            for (firstByte in 0..0xFF) {
                val subheaderIndex = keys[firstByte] / CMAP_FORMAT_2_SUBHEADER_BYTES
                val subheaderOffset = subheadersOffset + subheaderIndex * CMAP_FORMAT_2_SUBHEADER_BYTES
                val firstCode = buffer.uInt16(subheaderOffset)
                val entryCount = buffer.uInt16(subheaderOffset + 2)
                val idDelta = buffer.getShort(subheaderOffset + 4).toInt()
                val idRangeOffset = buffer.uInt16(subheaderOffset + 6)
                if (subheaderIndex == 0) {
                    if (firstByte !in firstCode until firstCode + entryCount) continue
                    val glyphPosition = subheaderOffset + 6 + idRangeOffset + (firstByte - firstCode) * 2
                    addLegacyGlyph(buffer, glyphPosition, firstByte, idDelta, subtableEnd)
                } else {
                    if (firstCode.toLong() + entryCount > 0x100L) continue
                    for (entryIndex in 0 until entryCount) {
                        val glyphPosition = subheaderOffset + 6 + idRangeOffset + entryIndex * 2
                        val encodedCode = firstByte shl 8 or (firstCode + entryIndex)
                        addLegacyGlyph(buffer, glyphPosition, encodedCode, idDelta, subtableEnd)
                    }
                }
            }
        }
    }

    private fun MutableMap<Int, Int>.addLegacyGlyph(
        buffer: ByteBuffer,
        glyphPosition: Int,
        encodedCode: Int,
        idDelta: Int,
        subtableEnd: Long,
    ) {
        if (glyphPosition < 0 || glyphPosition.toLong() + 2L > subtableEnd) return
        val rawGlyph = buffer.uInt16(glyphPosition)
        if (rawGlyph == 0) return
        val glyphId = (rawGlyph + idDelta) and 0xFFFF
        if (glyphId != 0) putIfAbsent(encodedCode, glyphId)
    }

    private fun legacyCharset(record: CmapEncodingRecord): Charset? {
        val charsetName = when (record.platformId) {
            WINDOWS_PLATFORM_ID -> when (record.encodingId) {
                WINDOWS_ENCODING_SHIFT_JIS -> "Shift_JIS"
                WINDOWS_ENCODING_PRC -> "GB18030"
                WINDOWS_ENCODING_BIG5 -> "Big5"
                WINDOWS_ENCODING_WANSUNG -> "EUC-KR"
                else -> null
            }
            MAC_PLATFORM_ID -> when (record.encodingId) {
                MAC_ENCODING_JAPANESE -> "Shift_JIS"
                MAC_ENCODING_TRADITIONAL_CHINESE -> "Big5"
                MAC_ENCODING_KOREAN -> "EUC-KR"
                MAC_ENCODING_SIMPLIFIED_CHINESE -> "GB18030"
                else -> null
            }
            else -> null
        } ?: return null
        return runCatching { Charset.forName(charsetName) }.getOrNull()
    }

    private fun decodeLegacyCodePoint(encodedCode: Int, charset: Charset): Int? {
        val bytes = if (encodedCode <= 0xFF) {
            byteArrayOf(encodedCode.toByte())
        } else {
            byteArrayOf((encodedCode ushr 8).toByte(), encodedCode.toByte())
        }
        val decoded = runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull() ?: return null
        if (decoded.isEmpty()) return null
        val codePoint = decoded.codePointAt(0)
        return codePoint.takeIf { Character.charCount(it) == decoded.length && Character.isValidCodePoint(it) }
    }

    private fun buildFormat12Cmap(mappings: Map<Int, Int>): ByteArray {
        val groups = buildList {
            var active: UnicodeCmapGroup? = null
            mappings.toSortedMap().forEach { (codePoint, glyphId) ->
                val current = active
                if (current != null && codePoint == current.endCodePoint + 1 &&
                    glyphId == current.startGlyphId + (codePoint - current.startCodePoint)
                ) {
                    active = current.copy(endCodePoint = codePoint)
                } else {
                    current?.let(::add)
                    active = UnicodeCmapGroup(codePoint, codePoint, glyphId)
                }
            }
            active?.let(::add)
        }
        require(groups.size <= MAX_CMAP_FORMAT_12_GROUPS)
        val subtableLength = CMAP_FORMAT_12_HEADER_BYTES + groups.size * CMAP_FORMAT_12_GROUP_BYTES
        val subtableOffset = 4 + 2 * CMAP_ENCODING_RECORD_BYTES
        return ByteArrayOutputStream(subtableOffset + subtableLength).also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeShort(0)
                output.writeShort(2)
                output.writeShort(UNICODE_PLATFORM_ID)
                output.writeShort(UNICODE_ENCODING_FULL_REPERTOIRE)
                output.writeInt(subtableOffset)
                output.writeShort(WINDOWS_PLATFORM_ID)
                output.writeShort(WINDOWS_ENCODING_UNICODE_FULL_REPERTOIRE)
                output.writeInt(subtableOffset)
                output.writeShort(CMAP_FORMAT_SEGMENTED_COVERAGE)
                output.writeShort(0)
                output.writeInt(subtableLength)
                output.writeInt(0)
                output.writeInt(groups.size)
                groups.forEach { group ->
                    output.writeInt(group.startCodePoint)
                    output.writeInt(group.endCodePoint)
                    output.writeInt(group.startGlyphId)
                }
            }
        }.toByteArray()
    }

    private fun ByteBuffer.uInt16(offset: Int): Int = getShort(offset).toInt() and 0xFFFF

    private fun ByteBuffer.uInt16OrNull(offset: Int): Int? =
        offset.takeIf { it >= 0 && it.toLong() + 2L <= limit() }?.let { uInt16(it) }

    private fun ByteBuffer.uInt32(offset: Int): Long? =
        offset.takeIf { it >= 0 && it.toLong() + 4L <= limit() }
            ?.let { getInt(it).toUInt().toLong() }

    private fun parseFace(
        input: RandomAccessFile,
        index: Int,
        offset: Long,
        preferredLocale: Locale,
        nameDecodeBudget: NameDecodeBudget,
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
        var aggregateTableBytes = 0L
        tables.forEach { table ->
            require(aggregateTableBytes <= input.length())
            require(table.length <= input.length() - aggregateTableBytes)
            aggregateTableBytes += table.length
        }
        val tablesByTag = tables.associateBy { it.tag }
        require(tablesByTag.size == tables.size)
        val names = tablesByTag[TAG_NAME]?.let { readNames(input, it, nameDecodeBudget) }.orEmpty()
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

    private fun readNames(
        input: RandomAccessFile,
        table: OpenTypeTable,
        nameDecodeBudget: NameDecodeBudget,
    ): List<OpenTypeName> {
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
                if (length > MAX_NAME_RECORD_BYTES) return@repeat
                val tableEnd = table.offset + table.length
                if (absoluteOffset < table.offset || !isRangeValid(absoluteOffset, length.toLong(), tableEnd)) {
                    return@repeat
                }
                if (!nameDecodeBudget.tryConsume(length)) return@repeat
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
        val buffer = ByteArray(EXTRACTION_BUFFER_SIZE)
        while (remaining > 0) {
            val size = minOf(buffer.size.toLong(), remaining).toInt()
            input.readFully(buffer, 0, size)
            if (clearChecksumAdjustment) {
                val adjustmentStart = HEAD_CHECKSUM_ADJUSTMENT_OFFSET
                val adjustmentEnd = adjustmentStart + 4
                val chunkEnd = tablePosition + size
                val clearStart = maxOf(tablePosition, adjustmentStart)
                val clearEnd = minOf(chunkEnd, adjustmentEnd)
                for (absolute in clearStart until clearEnd) {
                    buffer[(absolute - tablePosition).toInt()] = 0
                }
            }
            output.write(buffer, 0, size)
            checksum = checksumWords(buffer, size, checksum)
            tablePosition += size
            remaining -= size
        }
        return checksum
    }

    private fun checksum(file: RandomAccessFile): Long {
        file.seek(0)
        var remaining = file.length()
        var result = 0L
        val buffer = ByteArray(EXTRACTION_BUFFER_SIZE)
        while (remaining > 0) {
            val size = minOf(buffer.size.toLong(), remaining).toInt()
            file.readFully(buffer, 0, size)
            result = checksumWords(buffer, size, result)
            remaining -= size
        }
        return result
    }

    private fun checksumWords(buffer: ByteArray, size: Int, initial: Long): Long {
        val paddedSize = (size + 3) and -4
        if (paddedSize > size) buffer.fill(0, size, paddedSize)
        var result = initial
        var index = 0
        while (index < paddedSize) {
            val word =
                ((buffer[index].toLong() and 0xFF) shl 24) or
                    ((buffer[index + 1].toLong() and 0xFF) shl 16) or
                    ((buffer[index + 2].toLong() and 0xFF) shl 8) or
                    (buffer[index + 3].toLong() and 0xFF)
            result = (result + word) and UINT32_MASK
            index += 4
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

    private data class CmapEncodingRecord(
        val platformId: Int,
        val encodingId: Int,
        val subtableOffset: Int,
        val format: Int,
    ) {
        fun isChromiumUnicodeCmap(): Boolean {
            if (format !in CHROMIUM_UNICODE_CMAP_FORMATS) return false
            return platformId == UNICODE_PLATFORM_ID ||
                (platformId == WINDOWS_PLATFORM_ID && encodingId in WINDOWS_UNICODE_ENCODINGS)
        }
    }

    private data class UnicodeCmapGroup(
        val startCodePoint: Int,
        val endCodePoint: Int,
        val startGlyphId: Int,
    )

    private data class WrittenTable(
        val table: OpenTypeTable,
        val checksum: Long,
        val destinationOffset: Long,
    )

    private class NameDecodeBudget(private var remainingBytes: Int) {
        fun tryConsume(bytes: Int): Boolean {
            if (bytes < 0 || bytes > remainingBytes) return false
            remainingBytes -= bytes
            return true
        }
    }

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
        private const val TAG_CMAP = "cmap"
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
        private const val UNICODE_PLATFORM_ID = 0
        private const val MAC_PLATFORM_ID = 1
        private const val WINDOWS_PLATFORM_ID = 3
        private const val WINDOWS_ENCODING_SHIFT_JIS = 2
        private const val WINDOWS_ENCODING_PRC = 3
        private const val WINDOWS_ENCODING_BIG5 = 4
        private const val WINDOWS_ENCODING_WANSUNG = 5
        private const val WINDOWS_ENCODING_UNICODE_FULL_REPERTOIRE = 10
        private const val UNICODE_ENCODING_FULL_REPERTOIRE = 4
        private const val CMAP_FORMAT_HIGH_BYTE_MAPPING = 2
        private const val CMAP_FORMAT_SEGMENTED_COVERAGE = 12
        private const val CMAP_FORMAT_2_SUBHEADER_BYTES = 8
        private const val CMAP_FORMAT_2_MIN_BYTES = 526
        private const val CMAP_ENCODING_RECORD_BYTES = 8
        private const val CMAP_FORMAT_12_HEADER_BYTES = 16
        private const val CMAP_FORMAT_12_GROUP_BYTES = 12
        private const val MAX_CMAP_BYTES = 16L * 1024 * 1024
        private const val MAX_CMAP_RECORDS = 256
        private const val MAX_CMAP_FORMAT_12_GROUPS =
            (16 * 1024 * 1024 - CMAP_FORMAT_12_HEADER_BYTES) / CMAP_FORMAT_12_GROUP_BYTES
        private val CHROMIUM_UNICODE_CMAP_FORMATS = setOf(0, 4, 6, 10, 12, 13)
        private val WINDOWS_UNICODE_ENCODINGS = setOf(0, 1, 10)
        private const val MAX_FACE_COUNT = 128
        private const val MAX_TABLE_COUNT = 256
        private const val MAX_NAME_RECORD_COUNT = 512
        private const val MAX_NAME_RECORD_BYTES = 16 * 1024
        private const val MAX_TOTAL_NAME_BYTES = 4 * 1024 * 1024
        private const val MAX_AXIS_COUNT = 32
        private const val EXTRACTION_BUFFER_SIZE = 64 * 1024
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

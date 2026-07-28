package koharia.epub.font

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

class OpenTypeFontParserTest {

    @TempDir
    lateinit var directory: Path

    private val parser = OpenTypeFontParser()

    @Test
    fun `parses names style and variable weight from TrueType`() {
        val file = directory.resolve("sample.ttf")
        file.writeBytes(sfnt("Koharia Test", "KohariaTest-Regular", weight = 500, italic = true, variable = true))

        val parsed = parser.parse(file.toFile())
        val face = parsed.faces.single()

        assertFalse(parsed.isCollection)
        assertEquals("Koharia Test", face.familyName)
        assertEquals("KohariaTest-Regular", face.postScriptName)
        assertEquals("Koharia Fonts", face.manufacturer)
        assertEquals(500, face.weight)
        assertTrue(face.italic)
        assertEquals(100, face.minWeight)
        assertEquals(900, face.maxWeight)
        assertEquals(EpubFontFaceDescriptor.SFNT_TRUE_TYPE, face.sfntFlavor)
    }

    @Test
    fun `keeps canonical family name and exposes localized Chinese name`() {
        val file = directory.resolve("localized.ttf")
        file.writeBytes(
            sfnt(
                family = "Songti SC",
                postScript = "STSongti-SC-Regular",
                localizedFamily = "宋体-简",
            ),
        )

        val face = parser.parse(file.toFile(), Locale.SIMPLIFIED_CHINESE).faces.single()

        assertEquals("Songti SC", face.familyName)
        assertEquals("宋体-简", face.localizedFamilyName)
    }

    @Test
    fun `decodes legacy Macintosh simplified Chinese names`() {
        val file = directory.resolve("legacy-chinese.ttf")
        file.writeBytes(
            sfnt(
                family = "CT Zhong Yuan",
                postScript = "CTZhongYuanSJ",
                localizedFamily = "微软简中圆",
                macSimplifiedChineseName = true,
            ),
        )

        val face = parser.parse(file.toFile(), Locale.SIMPLIFIED_CHINESE).faces.single()

        assertEquals("CT Zhong Yuan", face.familyName)
        assertEquals("微软简中圆", face.localizedFamilyName)
    }

    @Test
    fun `repairs GB encoded bytes stored as Unicode code points`() {
        val file = directory.resolve("misencoded-chinese.ttf")
        file.writeBytes(
            sfnt(
                family = "CT Zhong Yuan",
                postScript = "CTZhongYuanSJ",
                localizedFamily = "微软简中圆",
                windowsMisencodedChineseName = true,
            ),
        )

        val face = parser.parse(file.toFile(), Locale.SIMPLIFIED_CHINESE).faces.single()

        assertEquals("CT Zhong Yuan", face.familyName)
        assertEquals("微软简中圆", face.localizedFamilyName)
    }

    @Test
    fun `parses and extracts individual faces from a collection`() {
        val firstOffset = 20
        val first = sfnt("Collection One", "CollectionOne", absoluteBase = firstOffset)
        val secondOffset = firstOffset + first.size
        val second = sfnt(
            family = "Collection Two",
            postScript = "CollectionTwo-Bold",
            weight = 700,
            cff = true,
            absoluteBase = secondOffset,
        )
        val collection = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeBytes("ttcf")
                output.writeInt(0x0001_0000)
                output.writeInt(2)
                output.writeInt(firstOffset)
                output.writeInt(secondOffset)
                output.write(first)
                output.write(second)
            }
        }.toByteArray()
        val source = directory.resolve("sample.ttc")
        source.writeBytes(collection)

        val parsed = parser.parse(source.toFile())
        assertTrue(parsed.isCollection)
        assertEquals(listOf("Collection One", "Collection Two"), parsed.faces.map { it.familyName })
        assertEquals(EpubFontFaceDescriptor.SFNT_CFF, parsed.faces[1].sfntFlavor)

        val extracted = directory.resolve("face.otf")
        parser.extractFace(source.toFile(), 1, extracted.toFile())
        val extractedFace = parser.parse(extracted.toFile()).faces.single()
        assertEquals("Collection Two", extractedFace.familyName)
        assertEquals(700, extractedFace.weight)
        assertEquals(0xB1B0_AFBAL, sfntChecksum(extracted.readBytes()))
    }

    @Test
    fun `normalizes misaligned tables in a standalone font`() {
        val source = directory.resolve("misaligned.ttf")
        source.writeBytes(
            misalignTableOffsets(
                sfnt("Misaligned Font", "MisalignedFont-Regular"),
            ),
        )
        assertTrue(tableOffsets(source.readBytes()).any { it % 4 != 0 })

        val destination = directory.resolve("normalized.ttf")
        parser.extractFace(source.toFile(), 0, destination.toFile())

        val normalized = destination.readBytes()
        assertTrue(tableOffsets(normalized).all { it % 4 == 0 })
        assertEquals("Misaligned Font", parser.parse(destination.toFile()).faces.single().familyName)
        assertEquals(0xB1B0_AFBAL, sfntChecksum(normalized))
    }

    @Test
    fun `converts legacy Chinese format 2 cmap to Unicode format 12`() {
        val source = directory.resolve("legacy-cmap.ttf")
        source.writeBytes(
            sfnt(
                family = "Legacy Chinese",
                postScript = "LegacyChinese-Regular",
                cmap = legacyChineseFormat2Cmap(),
            ),
        )

        val destination = directory.resolve("unicode-cmap.ttf")
        parser.extractFace(source.toFile(), 0, destination.toFile())

        val cmap = format12Mappings(destination.readBytes())
        assertEquals(1, cmap[0x0041])
        assertEquals(2, cmap[0x4E2D])
        assertEquals(0xB1B0_AFBAL, sfntChecksum(destination.readBytes()))
    }

    @Test
    fun `rejects truncated and unsupported data`() {
        val truncated = directory.resolve("broken.ttf")
        truncated.writeBytes(byteArrayOf(0, 1, 0, 0))
        assertThrows(IllegalArgumentException::class.java) { parser.parse(truncated.toFile()) }

        val unsupported = directory.resolve("font.woff")
        unsupported.writeBytes(ByteArray(16).also { "wOFF".toByteArray().copyInto(it) })
        assertThrows(IllegalArgumentException::class.java) { parser.parse(unsupported.toFile()) }
    }

    @Test
    fun `skips oversized name records without allocating their contents`() {
        val file = directory.resolve("oversized-name.ttf")
        file.writeBytes(
            sfnt(
                family = "A".repeat(9_000),
                postScript = "BoundedName-Regular",
            ),
        )

        val face = parser.parse(file.toFile()).faces.single()

        assertEquals("Font 1", face.familyName)
        assertEquals("BoundedName-Regular", face.postScriptName)
    }

    @Test
    fun `legacy preference values map to stable ids`() {
        assertEquals(EpubFontId.ORIGINAL, EpubFontId.fromPreference("ORIGINAL"))
        assertEquals(EpubFontId.SERIF, EpubFontId.fromPreference("SERIF"))
        assertEquals(EpubFontId.SANS_SERIF, EpubFontId.fromPreference("SANS_SERIF"))
        assertEquals(EpubFontId.MONOSPACE, EpubFontId.fromPreference("MONOSPACE"))
        assertEquals(EpubFontId.OPEN_DYSLEXIC, EpubFontId.fromPreference("OPEN_DYSLEXIC"))
        assertEquals(EpubFontId("local:example"), EpubFontId.fromPreference("local:example"))
    }

    private fun sfnt(
        family: String,
        postScript: String,
        weight: Int = 400,
        italic: Boolean = false,
        variable: Boolean = false,
        cff: Boolean = false,
        absoluteBase: Int = 0,
        localizedFamily: String? = null,
        macSimplifiedChineseName: Boolean = false,
        windowsMisencodedChineseName: Boolean = false,
        cmap: ByteArray? = null,
    ): ByteArray {
        val tables = linkedMapOf(
            "name" to nameTable(
                family,
                postScript,
                localizedFamily,
                macSimplifiedChineseName,
                windowsMisencodedChineseName,
            ),
            "OS/2" to ByteArray(64).also {
                ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).apply {
                    putShort(4, weight.toShort())
                    putShort(62, (if (italic) 1 else 0).toShort())
                }
            },
            "head" to ByteArray(54),
            "post" to ByteArray(32).also {
                if (italic) ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(4, 0x0001_0000)
            },
        )
        cmap?.let { tables["cmap"] = it }
        if (variable) {
            tables["fvar"] = ByteArray(36).also {
                ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).apply {
                    putInt(0, 0x0001_0000)
                    putShort(4, 16)
                    putShort(8, 1)
                    putShort(10, 20)
                    position(16)
                    put("wght".toByteArray())
                    putInt(100 shl 16)
                    putInt(400 shl 16)
                    putInt(900 shl 16)
                }
            }
        }
        val headerSize = 12 + tables.size * 16
        var nextOffset = headerSize
        val offsets = tables.mapValues { (_, bytes) ->
            val offset = nextOffset
            nextOffset += (bytes.size + 3) and -4
            offset
        }
        return ByteArrayOutputStream().also { result ->
            DataOutputStream(result).use { output ->
                if (cff) output.writeBytes("OTTO") else output.writeInt(0x0001_0000)
                output.writeShort(tables.size)
                output.writeShort(0)
                output.writeShort(0)
                output.writeShort(0)
                tables.forEach { (tag, bytes) ->
                    output.writeBytes(tag)
                    output.writeInt(0)
                    output.writeInt(absoluteBase + offsets.getValue(tag))
                    output.writeInt(bytes.size)
                }
                tables.forEach { (tag, bytes) ->
                    output.write(bytes)
                    repeat(((bytes.size + 3) and -4) - bytes.size) { output.write(0) }
                    check(result.size() == offsets.getValue(tag) + ((bytes.size + 3) and -4))
                }
            }
        }.toByteArray()
    }

    private fun nameTable(
        family: String,
        postScript: String,
        localizedFamily: String?,
        macSimplifiedChineseName: Boolean,
        windowsMisencodedChineseName: Boolean,
    ): ByteArray {
        val names = buildList {
            add(TestNameRecord(3, 1, 0x0409, 1, family, Charsets.UTF_16BE))
            add(TestNameRecord(3, 1, 0x0409, 2, "Regular", Charsets.UTF_16BE))
            add(TestNameRecord(3, 1, 0x0409, 6, postScript, Charsets.UTF_16BE))
            add(TestNameRecord(3, 1, 0x0409, 8, "Koharia Fonts", Charsets.UTF_16BE))
            add(TestNameRecord(3, 1, 0x0409, 16, family, Charsets.UTF_16BE))
            add(TestNameRecord(3, 1, 0x0409, 17, "Regular", Charsets.UTF_16BE))
            localizedFamily?.let { localized ->
                val platform = if (macSimplifiedChineseName) 1 else 3
                val encoding = if (macSimplifiedChineseName) 25 else 1
                val language = if (macSimplifiedChineseName) 33 else 0x0804
                val charset = if (macSimplifiedChineseName) Charset.forName("GB18030") else Charsets.UTF_16BE
                val storedName = if (windowsMisencodedChineseName) {
                    localized.toByteArray(Charset.forName("GB18030")).toString(Charsets.ISO_8859_1)
                } else {
                    localized
                }
                add(TestNameRecord(platform, encoding, language, 1, storedName, charset))
                add(TestNameRecord(platform, encoding, language, 16, storedName, charset))
            }
        }
        val encoded = names.map { it.value.toByteArray(it.charset) }
        val stringOffset = 6 + names.size * 12
        return ByteArrayOutputStream().also { result ->
            DataOutputStream(result).use { output ->
                output.writeShort(0)
                output.writeShort(names.size)
                output.writeShort(stringOffset)
                var offset = 0
                names.forEachIndexed { index, name ->
                    output.writeShort(name.platformId)
                    output.writeShort(name.encodingId)
                    output.writeShort(name.languageId)
                    output.writeShort(name.nameId)
                    output.writeShort(encoded[index].size)
                    output.writeShort(offset)
                    offset += encoded[index].size
                }
                encoded.forEach(output::write)
            }
        }.toByteArray()
    }

    private fun misalignTableOffsets(font: ByteArray): ByteArray {
        val input = ByteBuffer.wrap(font).order(ByteOrder.BIG_ENDIAN)
        val tableCount = input.getShort(4).toInt() and 0xFFFF
        val tableDataOffset = 12 + tableCount * 16
        val misaligned = ByteArray(font.size + 1)
        font.copyInto(misaligned, endIndex = tableDataOffset)
        font.copyInto(misaligned, destinationOffset = tableDataOffset + 1, startIndex = tableDataOffset)
        val output = ByteBuffer.wrap(misaligned).order(ByteOrder.BIG_ENDIAN)
        repeat(tableCount) { index ->
            val recordOffset = 12 + index * 16
            output.putInt(recordOffset + 8, input.getInt(recordOffset + 8) + 1)
        }
        return misaligned
    }

    private fun legacyChineseFormat2Cmap(): ByteArray {
        val subtableLength = 538
        val subtable = ByteBuffer.allocate(subtableLength).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(0, 2.toShort())
            putShort(2, subtableLength.toShort())
            putShort(4, 0.toShort())
            putShort(6 + 0xD6 * 2, 8.toShort())

            val subheader0 = 6 + 512
            putShort(subheader0, 0x41.toShort())
            putShort(subheader0 + 2, 1.toShort())
            putShort(subheader0 + 4, 0.toShort())
            putShort(subheader0 + 6, 10.toShort())

            val subheader1 = subheader0 + 8
            putShort(subheader1, 0xD0.toShort())
            putShort(subheader1 + 2, 1.toShort())
            putShort(subheader1 + 4, 0.toShort())
            putShort(subheader1 + 6, 4.toShort())
            putShort(subheader1 + 8, 1.toShort())
            putShort(subheader1 + 10, 2.toShort())
        }.array()
        return ByteBuffer.allocate(12 + subtable.size).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(0.toShort())
            putShort(1.toShort())
            putShort(3.toShort())
            putShort(3.toShort())
            putInt(12)
            put(subtable)
        }.array()
    }

    private fun format12Mappings(font: ByteArray): Map<Int, Int> {
        val buffer = ByteBuffer.wrap(font).order(ByteOrder.BIG_ENDIAN)
        val tableCount = buffer.getShort(4).toInt() and 0xFFFF
        val cmapOffset = (0 until tableCount).firstNotNullOf { index ->
            val recordOffset = 12 + index * 16
            val tag = font.copyOfRange(recordOffset, recordOffset + 4).toString(Charsets.ISO_8859_1)
            buffer.getInt(recordOffset + 8).takeIf { tag == "cmap" }
        }
        val recordCount = buffer.getShort(cmapOffset + 2).toInt() and 0xFFFF
        val format12Offset = (0 until recordCount).firstNotNullOf { index ->
            val recordOffset = cmapOffset + 4 + index * 8
            val platformId = buffer.getShort(recordOffset).toInt() and 0xFFFF
            val encodingId = buffer.getShort(recordOffset + 2).toInt() and 0xFFFF
            val subtableOffset = cmapOffset + buffer.getInt(recordOffset + 4)
            subtableOffset.takeIf {
                platformId == 3 && encodingId == 10 && (buffer.getShort(it).toInt() and 0xFFFF) == 12
            }
        }
        val groupCount = buffer.getInt(format12Offset + 12)
        return buildMap {
            repeat(groupCount) { index ->
                val groupOffset = format12Offset + 16 + index * 12
                val start = buffer.getInt(groupOffset)
                val end = buffer.getInt(groupOffset + 4)
                val startGlyph = buffer.getInt(groupOffset + 8)
                for (codePoint in start..end) {
                    put(codePoint, startGlyph + codePoint - start)
                }
            }
        }
    }

    private fun tableOffsets(font: ByteArray): List<Int> {
        val buffer = ByteBuffer.wrap(font).order(ByteOrder.BIG_ENDIAN)
        val tableCount = buffer.getShort(4).toInt() and 0xFFFF
        return List(tableCount) { index -> buffer.getInt(12 + index * 16 + 8) }
    }

    private data class TestNameRecord(
        val platformId: Int,
        val encodingId: Int,
        val languageId: Int,
        val nameId: Int,
        val value: String,
        val charset: Charset,
    )

    private fun sfntChecksum(bytes: ByteArray): Long {
        val padded = bytes.copyOf((bytes.size + 3) and -4)
        val buffer = ByteBuffer.wrap(padded).order(ByteOrder.BIG_ENDIAN)
        var checksum = 0L
        while (buffer.remaining() >= 4) {
            checksum = (checksum + buffer.int.toUInt().toLong()) and 0xFFFF_FFFFL
        }
        return checksum
    }
}

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

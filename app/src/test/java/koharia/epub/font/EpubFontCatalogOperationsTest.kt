package koharia.epub.font

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubFontCatalogOperationsTest {

    @Test
    fun `reimport restores faces missing from a retained collection source`() {
        val retained = storedSource(faceIndices = listOf(0))
        val parsed = ParsedOpenTypeFile(
            isCollection = true,
            faces = listOf(parsedFace(0), parsedFace(1)),
        )

        val missing = parsed.missingFrom(retained)
        val addition = retained.copy(faces = missing.map(::storedFace))
        val merged = mergeStoredFontSources(listOf(retained), listOf(addition))

        assertEquals(listOf(1), missing.map(ParsedOpenTypeFace::index))
        assertEquals(listOf(0, 1), merged.single().faces.map(StoredEpubFontFace::faceIndex))
    }

    private fun storedSource(faceIndices: List<Int>) = StoredEpubFontSource(
        checksum = "collection-checksum",
        storedFileName = "collection.ttc",
        originalFileName = "collection.ttc",
        size = 1_024L,
        faces = faceIndices.map { storedFace(parsedFace(it)) },
    )

    private fun parsedFace(index: Int) = ParsedOpenTypeFace(
        index = index,
        familyName = "Family $index",
        localizedFamilyName = null,
        manufacturer = null,
        postScriptName = "Family$index-Regular",
        weight = 400,
        italic = false,
        minWeight = 400,
        maxWeight = 400,
        sfntSignature = "\u0000\u0001\u0000\u0000",
        sfntFlavor = EpubFontFaceDescriptor.SFNT_TRUE_TYPE,
        tables = emptyList(),
    )

    private fun storedFace(face: ParsedOpenTypeFace) = StoredEpubFontFace(
        faceIndex = face.index,
        familyName = face.familyName,
        localizedFamilyName = face.localizedFamilyName,
        manufacturer = face.manufacturer,
        postScriptName = face.postScriptName,
        weight = face.weight,
        italic = face.italic,
        minWeight = face.minWeight,
        maxWeight = face.maxWeight,
        sfntFlavor = face.sfntFlavor,
    )
}

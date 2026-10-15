package koharia.pdf

internal fun reflowTextPdfFixture(): ByteArray {
    fun bytes(text: String) = text.toByteArray(Charsets.ISO_8859_1)
    val objects = mutableListOf<ByteArray>()
    objects += bytes("<< /Type /Catalog /Pages 2 0 R >>")
    objects += bytes("<< /Type /Pages /Count 4 /Kids [3 0 R 5 0 R 7 0 R 9 0 R] >>")
    repeat(4) { page ->
        objects += bytes(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 900] " +
                "/Resources << /Font << /F1 11 0 R >> >> /Contents ${4 + page * 2} 0 R >>",
        )
        val content = buildString {
            repeat(36) { line ->
                append("BT /F1 12 Tf 20 ${860 - line * 22} Td ")
                append(
                    "(Original page $page line $line. The river flows through the quiet garden and valley.) Tj ET\n",
                )
            }
        }
        objects += bytes("<< /Length ${bytes(content).size} >>\nstream\n$content\nendstream")
    }
    objects += bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    val output = java.io.ByteArrayOutputStream()
    output.write(bytes("%PDF-1.4\n"))
    val offsets = objects.mapIndexed { i, obj ->
        output.size().also {
            output.write(bytes("${i + 1} 0 obj\n"))
            output.write(obj)
            output.write(bytes("\nendobj\n"))
        }
    }
    val xref = output.size()
    output.write(bytes("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n"))
    offsets.forEach { output.write(bytes(it.toString().padStart(10, '0') + " 00000 n \n")) }
    output.write(bytes("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF"))
    return output.toByteArray()
}

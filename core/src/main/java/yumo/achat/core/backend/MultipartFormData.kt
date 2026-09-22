package yumo.achat.core.backend

import java.io.OutputStream

object MultipartFormData {
    sealed interface Part {
        val fieldName: String
        val headers: String
        val bytes: ByteArray
    }

    data class FilePart(
        override val fieldName: String,
        val fileName: String,
        val contentType: String,
        override val bytes: ByteArray,
    ) : Part {
        override val headers: String
            get() = buildString {
                append("Content-Disposition: form-data; name=\"")
                append(fieldName)
                append("\"; filename=\"")
                append(fileName)
                append("\"\r\n")
                append("Content-Type: ")
                append(contentType)
                append("\r\n")
            }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FilePart) return false
            return fieldName == other.fieldName &&
                fileName == other.fileName &&
                contentType == other.contentType &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = fieldName.hashCode()
            result = 31 * result + fileName.hashCode()
            result = 31 * result + contentType.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    data class TextPart(
        override val fieldName: String,
        val value: String,
    ) : Part {
        override val headers: String
            get() = "Content-Disposition: form-data; name=\"$fieldName\"\r\n"
        override val bytes: ByteArray
            get() = value.toByteArray(Charsets.UTF_8)
    }

    fun imagePart(
        fieldName: String,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
    ): FilePart = FilePart(
        fieldName = fieldName,
        fileName = fileName.escapeMultipartFileName(),
        contentType = contentType.ifBlank { "image/jpeg" },
        bytes = bytes,
    )

    fun textPart(fieldName: String, value: String): TextPart =
        TextPart(fieldName = fieldName, value = value)

    fun write(outputStream: OutputStream, boundary: String, part: Part) {
        write(outputStream, boundary, listOf(part))
    }

    fun write(outputStream: OutputStream, boundary: String, parts: List<Part>) {
        parts.forEach { part ->
            outputStream.writePart(boundary, part)
        }
        outputStream.writeUtf8("--$boundary--\r\n")
    }

    private fun OutputStream.writePart(boundary: String, part: Part) {
        writeUtf8("--$boundary\r\n")
        writeUtf8(part.headers)
        writeUtf8("\r\n")
        write(part.bytes)
        writeUtf8("\r\n")
    }

    private fun String.escapeMultipartFileName(): String =
        replace("\"", "%22")
            .replace("\r", "")
            .replace("\n", "")
}

private fun OutputStream.writeUtf8(value: String) {
    write(value.toByteArray(Charsets.UTF_8))
}

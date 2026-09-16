package yumo.achat.app.data.backend

import java.io.OutputStream

object MultipartFormData {
    data class Part(
        val fieldName: String,
        val fileName: String,
        val contentType: String,
        val bytes: ByteArray,
    ) {
        val headers: String
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
            if (other !is Part) return false
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

    fun imagePart(
        fieldName: String,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
    ): Part = Part(
        fieldName = fieldName,
        fileName = fileName.escapeMultipartFileName(),
        contentType = contentType.ifBlank { "image/jpeg" },
        bytes = bytes,
    )

    fun write(outputStream: OutputStream, boundary: String, part: Part) {
        outputStream.writeUtf8("--$boundary\r\n")
        outputStream.writeUtf8(part.headers)
        outputStream.writeUtf8("\r\n")
        outputStream.write(part.bytes)
        outputStream.writeUtf8("\r\n--$boundary--\r\n")
    }

    private fun String.escapeMultipartFileName(): String =
        replace("\"", "%22")
            .replace("\r", "")
            .replace("\n", "")
}

private fun OutputStream.writeUtf8(value: String) {
    write(value.toByteArray(Charsets.UTF_8))
}

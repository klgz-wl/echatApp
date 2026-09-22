package yumo.achat.core.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MultipartFormDataTest {
    @Test
    fun `creates image part with escaped filename and content type`() {
        val part = MultipartFormData.imagePart(
            fieldName = "image",
            fileName = "my \"photo\".webp",
            contentType = "image/webp",
            bytes = byteArrayOf(1, 2, 3),
        )

        assertEquals("image", part.fieldName)
        assertEquals("my %22photo%22.webp", part.fileName)
        assertEquals("image/webp", part.contentType)
        assertTrue(part.headers.contains("Content-Disposition: form-data; name=\"image\"; filename=\"my %22photo%22.webp\""))
        assertTrue(part.headers.contains("Content-Type: image/webp"))
    }

    @Test
    fun `writes text fields and file parts in one multipart body`() {
        val output = ByteArrayOutputStream()
        MultipartFormData.write(
            outputStream = output,
            boundary = "boundary",
            parts = listOf(
                MultipartFormData.textPart("template_id", "template-1"),
                MultipartFormData.textPart("resource_id", "resource-1"),
            ),
        )

        val body = output.toString(Charsets.UTF_8.name())

        assertTrue(body.contains("Content-Disposition: form-data; name=\"template_id\""))
        assertTrue(body.contains("template-1"))
        assertTrue(body.contains("Content-Disposition: form-data; name=\"resource_id\""))
        assertTrue(body.contains("resource-1"))
        assertTrue(body.endsWith("--boundary--\r\n"))
    }
}

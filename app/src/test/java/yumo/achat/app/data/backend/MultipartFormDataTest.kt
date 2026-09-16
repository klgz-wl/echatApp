package yumo.achat.app.data.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}

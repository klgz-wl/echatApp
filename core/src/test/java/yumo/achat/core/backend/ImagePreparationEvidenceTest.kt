package yumo.achat.core.backend

import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ImagePreparationEvidenceTest {
    @Test
    fun `local resolver failure preserves cause and message`() {
        val denied = SecurityException("Selected photo permission expired")
        val error = assertThrows(ImagePreparationException::class.java) {
            prepareImageUpload<MultipartFormData.Part> { throw denied }
        }
        assertSame(denied, error.cause)
        assertEquals(denied.message, error.message)
    }

    @Test
    fun `stream read failure is preparation evidence`() {
        val failure = IOException("Unable to read selected image")
        val stream = object : InputStream() {
            override fun read(): Int = throw failure
        }
        val error = assertThrows(ImagePreparationException::class.java) {
            prepareImageUpload { stream.use(::readProfileAvatarBytes) }
        }
        assertSame(failure, error.cause)
        assertEquals(failure.message, error.message)
    }

    @Test
    fun `avatar type and multipart size validation are local preparation`() {
        val typeError = assertThrows(ImagePreparationException::class.java) {
            prepareImageUpload { validatedProfileAvatarContentType("application/pdf") }
        }
        assertEquals("Selected file must be an image", typeError.message)
        val sizeError = assertThrows(ImagePreparationException::class.java) {
            prepareImageUpload {
                profileAvatarUploadParts("avatar.jpg", "image/jpeg", ByteArray(MAX_PROFILE_AVATAR_BYTES + 1))
            }
        }
        assertEquals("Profile photo must be 20 MB or smaller", sizeError.message)
    }

    @Test
    fun `cancellation during preparation propagates unchanged`() {
        val cancellation = CancellationException("cancelled")
        val error = assertThrows(CancellationException::class.java) {
            prepareImageUpload<ByteArray> { throw cancellation }
        }
        assertSame(cancellation, error)
    }

    @Test
    fun `successful preparation preserves multipart contract`() {
        val bytes = byteArrayOf(1, 2, 3)
        val part = prepareImageUpload {
            MultipartFormData.imagePart("image", "photo.jpg", "image/jpeg", bytes)
        }
        assertEquals("image", part.fieldName)
        assertSame(bytes, part.bytes)
    }

    @Test
    fun `only existing blank creation inputs prevent submission with original messages`() {
        val failures = listOf(
            Triple(" ", "resource", "key") to "Live template is required",
            Triple("template", "\t", "key") to "Uploaded photo is required",
            Triple("template", "resource", "") to "Generation idempotency key is required",
            Triple("", "", "") to "Live template is required",
        )
        for ((inputs, message) in failures) {
            val error = assertThrows(GenerationNotSubmittedException::class.java) {
                validateGenerationCreationInputs(inputs.first, inputs.second, inputs.third)
            }
            assertEquals(message, error.message)
        }
        validateGenerationCreationInputs(" template ", "resource", "key")
    }

}

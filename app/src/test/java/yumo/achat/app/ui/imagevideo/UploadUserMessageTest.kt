package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UploadUserMessageTest {
    @Test
    fun `api envelope user message hides trace fields`() {
        assertEquals(
            "Catalog unavailable",
            apiEnvelopeUserMessage(
                """{"message":"Catalog unavailable","trace":"secret-stack"}""",
                "Fallback",
            ),
        )
        assertEquals(
            "Payment route unavailable",
            apiEnvelopeUserMessage(
                """{"error":"Payment route unavailable","trace":"secret-stack"}""",
                "Fallback",
            ),
        )
    }

    @Test
    fun `uses backend json message as user message`() {
        val rawError = """
            {"code":400101,"message":"Insufficient diamond balance","trace":"e322e655be2f55954a0b70bd550d592a","type":""}
        """.trimIndent()

        val message = visualGenerationUserMessage(rawError, fallback = "Upload failed")

        assertEquals("Insufficient diamond balance", message)
        assertFalse(message.contains("\"code\""))
        assertFalse(message.contains("trace"))
    }
}

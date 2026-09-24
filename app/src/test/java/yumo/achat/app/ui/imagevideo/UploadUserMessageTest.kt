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
    fun `payment channel unavailable code is sanitized for top up`() {
        val fallback = "Unable to prepare payment"
        val unavailable = "Payment is temporarily unavailable. Please try again later."

        assertEquals(
            unavailable,
            topUpPaymentPrepareUserMessage(
                "PAYMENT_CHANNEL_UNAVAILABLE",
                fallback,
                unavailable,
            ),
        )
        assertEquals(
            unavailable,
            topUpPaymentPrepareUserMessage(
                """{"error":"PAYMENT_CHANNEL_UNAVAILABLE"}""",
                fallback,
                unavailable,
            ),
        )
        assertEquals(
            "payment initialization failed",
            topUpPaymentPrepareUserMessage(
                "payment initialization failed",
                fallback,
                unavailable,
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

    @Test
    fun `insufficient balance generation error routes to top up`() {
        assertEquals(
            true,
            shouldRouteGenerationErrorToTopUp(
                """{"code":400101,"message":"Insufficient diamond balance","trace":"secret"}""",
            ),
        )
        assertEquals(true, shouldRouteGenerationErrorToTopUp("insufficient diamond balance"))
        assertEquals(false, shouldRouteGenerationErrorToTopUp("Upload failed"))
        assertEquals(false, shouldRouteGenerationErrorToTopUp(null))
    }

    @Test
    fun `insufficient balance top up route is delayed so the message is visible`() {
        assertEquals(
            1200L,
            generationTopUpRouteDelayMillis(
                """{"code":400101,"message":"Insufficient diamond balance","trace":"secret"}""",
            ),
        )
        assertEquals(null, generationTopUpRouteDelayMillis("Upload failed"))
    }

    @Test
    fun `google billing disconnected message is sanitized for top up`() {
        val fallback = "Google Play is temporarily unavailable. Please try again."

        assertEquals(
            fallback,
            googleBillingUserMessage("Service connection is disconnected.", fallback),
        )
        assertEquals(
            fallback,
            googleBillingUserMessage("", fallback),
        )
        assertEquals(
            "Product unavailable",
            googleBillingUserMessage("Product unavailable", fallback),
        )
    }
}

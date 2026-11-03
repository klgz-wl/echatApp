package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UploadUserMessageTest {
    @Test
    fun `typed technical failures use localized fallback instead of raw messages`() {
        val fallback = "Upload failed"
        assertEquals(fallback, operationUserMessage(java.io.IOException("raw connection state"), fallback))
        assertEquals(fallback, operationUserMessage(org.json.JSONException("task_id parse failed"), fallback))
        assertEquals("Insufficient diamond balance", operationUserMessage(
            yumo.achat.core.backend.GenerationCreationRejectedException(400101, "Insufficient diamond balance"), fallback))
    }

    @Test
    fun `irrelevant envelope metadata cannot change generation balance routing`() {
        val raw = """{"code":400101,"message":"Insufficient diamond balance","trace":"https://internal.example/${"x".repeat(1024)}"}"""
        assertEquals("Insufficient diamond balance", visualGenerationUserMessage(raw, "Upload failed"))
        assertEquals(1200L, generationTopUpRouteDelayMillis(raw))
    }

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
    fun `api envelope user message hides html and oversized responses`() {
        val fallback = "Unable to load video templates"

        assertEquals(
            fallback,
            apiEnvelopeUserMessage(
                """<!doctype html><html><head><title>Unauthorized</title></head></html>""",
                fallback,
            ),
        )
        assertEquals(
            fallback,
            apiEnvelopeUserMessage("x".repeat(513), fallback),
        )
        assertEquals(
            fallback,
            apiEnvelopeUserMessage("502 Bad Gateway <body>Proxy unavailable</body>", fallback),
        )
        assertEquals(
            fallback,
            apiEnvelopeUserMessage(
                """{"message":"Proxy rejected <script>details</script>"}""",
                fallback,
            ),
        )
        assertEquals("Service unavailable", apiEnvelopeUserMessage("Service unavailable", fallback))
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

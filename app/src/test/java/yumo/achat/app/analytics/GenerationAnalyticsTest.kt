package yumo.achat.app.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import yumo.achat.core.analytics.EventTracker
import yumo.achat.core.backend.VisualGenerationTask

class GenerationAnalyticsTest {
    @Test
    fun `generate click and accepted submit use same request id`() {
        val tracker = RecordingTracker()
        val analytics = GenerationAnalytics(tracker) { "user-1" }
        val context = GenerationAnalyticsContext("template-1", "category-1", "video", "video", "fast", 9)

        analytics.clickGenerate(context, "request-1")
        analytics.submitResult(context, "request-1", task = task(status = "pending"), error = null)

        assertEquals("request-1", tracker.single("click_generate").parameters["request_id"])
        assertEquals(9L, tracker.single("click_generate").parameters["diamond_cost"])
        assertEquals("request-1", tracker.single("upload_img_result").parameters["request_id"])
        assertEquals(true, tracker.single("upload_img_result").parameters["is_success"])
    }

    @Test
    fun `failed submit is sanitized to controlled reason`() {
        val tracker = RecordingTracker()
        val analytics = GenerationAnalytics(tracker) { "user-1" }
        val context = GenerationAnalyticsContext("template-1", "", "image", "image", "quality", 5)

        analytics.submitResult(context, "request-1", task = null, error = IllegalStateException("Bearer secret trace"))

        val event = tracker.single("upload_img_result")
        assertEquals(false, event.parameters["is_success"])
        assertEquals("unknown", event.parameters["fail_reason"])
        assertFalse(event.parameters.values.contains("Bearer secret trace"))
    }

    @Test
    fun `terminal generation result is deduped by task`() {
        val tracker = RecordingTracker()
        val analytics = GenerationAnalytics(tracker) { "user-1" }
        val context = GenerationAnalyticsContext("template-1", "", "video", "video", "fast", 9)
        val task = task(status = "succeeded")

        analytics.terminalResult(context, "request-1", task)
        analytics.terminalResult(context, "request-1", task)

        assertEquals(2, tracker.named("generate_result").size)
        assertEquals("generation:task-1:terminal", tracker.named("generate_result").first().onceKey)
        assertEquals(true, tracker.named("generate_result").first().parameters["is_success"])
    }

    private fun task(status: String) = VisualGenerationTask(
        taskId = "task-1", status = status, modality = "video", quality = "fast",
        templateId = "template-1", diamondCost = 9, estimatedPollIntervalSeconds = null,
        refunded = false, errorMessage = null, resource = null,
    )

    private class RecordingTracker : EventTracker {
        data class Event(val name: String, val parameters: Map<String, Any>, val onceKey: String?)
        val events = mutableListOf<Event>()
        override fun track(name: String, parameters: Map<String, Any>, userId: String?, onceKey: String?) {
            events += Event(name, parameters, onceKey)
        }
        fun named(name: String) = events.filter { it.name == name }
        fun single(name: String) = named(name).single()
    }
}

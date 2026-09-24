package yumo.achat.app.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class FirebaseAnalyticsSinkTest {
    @Test
    fun `firebase sink prefixes events and synchronizes identity`() {
        val client = FakeFirebaseClient()
        val sink = FirebaseAnalyticsSink(client)

        sink.initialize()
        sink.identify("user-1")
        sink.event("page_view", mapOf("page_name" to "video", "position" to 2L))

        assertEquals(1, client.initializeCount)
        assertEquals("user-1", client.userId)
        assertEquals("f_page_view", client.eventName)
        assertEquals(2L, client.parameters["position"])
    }

    private class FakeFirebaseClient : FirebaseClient {
        var initializeCount = 0
        var userId: String? = null
        var eventName = ""
        var parameters: Map<String, Any> = emptyMap()
        override fun initialize() { initializeCount += 1 }
        override fun identify(userId: String?) { this.userId = userId }
        override fun event(name: String, parameters: Map<String, Any>) {
            eventName = name
            this.parameters = parameters
        }
    }
}

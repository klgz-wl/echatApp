package yumo.achat.core.integration.appsflyer

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversionCallbackEventsTest {
    @Test fun `consecutive true callbacks remain distinct before subscriber catches up`() = runBlocking {
        val events = ConversionCallbackEvents<Int>()
        repeat(128) { events.publish(it) }

        assertEquals((0 until 128).toList(), events.events.take(128).toList())
    }
}

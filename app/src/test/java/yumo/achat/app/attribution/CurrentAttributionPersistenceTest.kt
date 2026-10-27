package yumo.achat.app.attribution

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import yumo.achat.core.attribution.AttributionResult
import yumo.achat.core.attribution.AttributionResultSource
import yumo.achat.core.attribution.ConversionResult

class CurrentAttributionPersistenceTest {
    private val data = buildJsonObject { put("af_status", "Organic") }
    private val success = ConversionResult.Success(data, AttributionResultSource.APPSFLYER_SDK)

    @Test fun `current success is persisted when async delivery was missed`() = runBlocking {
        val persisted = mutableListOf<AttributionResult>()

        persistCurrentAttributionIfMissing(
            current = success,
            hasResult = { source, value -> persisted.any { it.source == source && it.data == value } },
            persist = { persisted.add(it) },
        )

        assertEquals(listOf(AttributionResult(success.source, success.data)), persisted)
    }

    @Test fun `current success is not persisted twice`() = runBlocking {
        val persisted = mutableListOf(AttributionResult(success.source, success.data))

        persistCurrentAttributionIfMissing(
            current = success,
            hasResult = { source, value -> persisted.any { it.source == source && it.data == value } },
            persist = { persisted.add(it) },
        )

        assertEquals(1, persisted.size)
    }

    @Test fun `pending and failed conversion are never persisted`() = runBlocking {
        val persisted = mutableListOf<AttributionResult>()

        listOf(ConversionResult.Pending, ConversionResult.Failed).forEach { current ->
            persistCurrentAttributionIfMissing(
                current = current,
                hasResult = { _, _ -> false },
                persist = { persisted.add(it) },
            )
        }

        assertEquals(emptyList<AttributionResult>(), persisted)
    }
}

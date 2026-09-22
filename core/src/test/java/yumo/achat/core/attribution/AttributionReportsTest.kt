package yumo.achat.core.attribution

import yumo.achat.core.auth.*
import yumo.achat.core.config.ClientIdentity
import yumo.achat.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class AttributionReportsTest {
    @Test fun `迟到归因补报后刷新资料切换模式且补报或资料失败均重试`() = runBlocking {
        val storage = MemoryStorage(); val sessions = SessionCoordinator(storage)
        sessions.saveLogin(AuthResponse("token", "refresh", "anonymous"))
        val epoch = sessions.current!!.epoch
        val source = object : ConversionSource {
            override val conversion = MutableStateFlow<ConversionResult>(ConversionResult.Failed)
            override fun startAttribution() = Unit
            override fun attributionUid() = "af"
            override fun advertisingId(): String? = null
        }
        val identity = ClientIdentity("test.package", "1.0", 30)
        val config = AttributionConfiguration(20, 10, 20)
        val coordinator = AttributionCoordinator(source, object : AttributionStorage {
            override suspend fun readAttribution(): JsonObject? = null
            override suspend fun writeAttribution(data: JsonObject) = Unit
        }, storage, identity, config)
        coordinator.awaitInitial()
        val sent = CopyOnWriteArrayList<JsonObject>()
        val profileUsers = CopyOnWriteArrayList<String>()
        val profiles = UserProfileRepository(object : UserProfileApi {
            override suspend fun profile(session: Session): ApiResponse<UserProfile> {
                if (sent.isEmpty()) return ApiResponse(0, data = UserProfile(false))
                assertTrue(sent.size >= 2)
                profileUsers += session.userId
                if (profileUsers.size == 1) throw java.io.IOException()
                return ApiResponse(0, data = UserProfile(true))
            }
        }, sessions)
        profiles.refresh()
        assertEquals(yumo.achat.core.config.AppMode.A, profiles.state.value?.mode)
        AttributionReports(coordinator, object : AttributionReportApi {
            override suspend fun report(payload: JsonObject): ApiResponse<Unit> {
                sent += payload
                if (sent.size == 1) throw java.io.IOException()
                return ApiResponse(0)
            }
        }, sessions, storage, identity, config, profiles).start()
        val raw = buildJsonObject { put("af_status", "Non-organic"); put("is_first_launch", true) }
        source.conversion.value = ConversionResult.Success(raw)
        withTimeout(2000) { while (profiles.state.value?.mode != yumo.achat.core.config.AppMode.B) delay(5) }
        assertEquals(raw, sent.last()["extra_data"])
        assertEquals(JsonPrimitive("anonymous"), sent.last()["user_id"])
        assertEquals(sent.first(), sent.last())
        assertEquals(listOf("anonymous", "anonymous"), profileUsers.toList())
        assertEquals(epoch, sessions.current!!.epoch)
        sessions.saveLogin(AuthResponse("new", "refresh", "other"))
        withTimeout(2000) { while (profiles.state.value?.epoch != sessions.current?.epoch) delay(5) }
        assertEquals(JsonPrimitive("other"), sent.last()["user_id"])
    }
}

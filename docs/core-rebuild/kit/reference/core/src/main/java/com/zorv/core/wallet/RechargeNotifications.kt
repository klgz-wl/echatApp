package com.zorv.core.wallet

import com.zorv.core.auth.SessionCoordinator
import com.zorv.core.config.CoreRuntimeConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class RechargeNotice(val epoch: String, val key: String, val orderId: String? = null)
data class RechargeStreamConfiguration(val initialRetry: Long, val maxRetry: Long, val firstMessageTimeout: Long,
    val idleTimeout: Long, val checkInterval: Long, val dedupeWindow: Long)

/** 只迁移参考 Centrifugo 的充值通知分支，不引入聊天业务。 */
@Singleton
class RechargeNotifications @Inject constructor(@Named("publicClient") private val client: OkHttpClient,
    private val runtime: CoreRuntimeConfig, private val config: RechargeStreamConfiguration,
    private val sessions: SessionCoordinator, private val wallet: WalletRepository, private val json: Json) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val foreground = MutableStateFlow(false)
    private val mutable = MutableSharedFlow<RechargeNotice>(extraBufferCapacity = 8)
    val events = mutable.asSharedFlow()
    init {
        scope.launch {
            combine(foreground, sessions.state) { visible, session -> session?.takeIf { visible } }
                .collectLatest { session ->
                    if (session == null) return@collectLatest
                    var retry = config.initialRetry
                    var lastKey: String? = null
                    var lastAt = 0L
                    while (currentCoroutineContext().isActive) {
                        val closed = CompletableDeferred<Unit>()
                        val started = System.currentTimeMillis()
                        val lastMessage = AtomicLong(0)
                        val socket = client.newWebSocket(Request.Builder().url(runtime.network.streamUrl.trimEnd('/') + "/connection/websocket").build(), object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send(buildJsonObject {
                                    put("id", 1); putJsonObject("connect") { putJsonObject("data") { put("token", session.token) } }
                                }.toString())
                            }
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                if (!foreground.value || sessions.current?.epoch != session.epoch) return
                                lastMessage.set(System.currentTimeMillis())
                                if (text.trim() in listOf("null", "{}", "{ }")) { webSocket.send("{}"); return }
                                text.lineSequence().filter(String::isNotBlank).forEach { line ->
                                    try {
                                        val envelope = json.parseToJsonElement(line).jsonObject
                                        if (envelope.containsKey("disconnect") || envelope.containsKey("error")) { closed.complete(Unit); return }
                                        val key = rechargeKey(envelope) ?: return@forEach
                                        val now = System.currentTimeMillis()
                                        synchronized(this@RechargeNotifications) {
                                            if (key == lastKey && now - lastAt < config.dedupeWindow) return@forEach
                                            lastKey = key; lastAt = now
                                        }
                                        scope.launch {
                                            if (sessions.current?.epoch != session.epoch) return@launch
                                            mutable.emit(RechargeNotice(session.epoch, key, rechargeOrderId(envelope)))
                                            try { wallet.refreshBalance(); wallet.loadRecords(refresh = true); wallet.refreshProducts() }
                                            catch (cancelled: CancellationException) { throw cancelled }
                                            catch (_: Exception) { /* 通知不替代真实余额，读取失败可重试。 */ }
                                        }
                                    } catch (_: Exception) { /* 与参考相同，忽略损坏或不相关的通知。 */ }
                                }
                            }
                            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                                if (bytes.size == 0) { lastMessage.set(System.currentTimeMillis()); webSocket.send("{}") }
                            }
                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                        })
                        try {
                            while (!closed.isCompleted) {
                                delay(config.checkInterval)
                                val last = lastMessage.get()
                                val deadline = if (last == 0L) started + config.firstMessageTimeout else last + config.idleTimeout
                                if (System.currentTimeMillis() > deadline) break
                            }
                        } finally { socket.cancel() }
                        if (lastMessage.get() != 0L) retry = config.initialRetry
                        delay(retry)
                        retry = (retry * 2).coerceAtMost(config.maxRetry)
                    }
                }
        }
    }
    fun setForeground(value: Boolean) { foreground.value = value }
    fun isCurrent(notice: RechargeNotice) = sessions.current?.epoch == notice.epoch
}

/** 原协议 notification_type/type、notification_id 与订单键的回退顺序。 */
internal fun rechargeKey(envelope: JsonObject): String? {
    val publication = (envelope["pub"] ?: (envelope["push"] as? JsonObject)?.get("pub")) as? JsonObject ?: return null
    val data = publication["data"] as? JsonObject ?: return null
    if ((data["message_type"] as? JsonPrimitive)?.content != "notification") return null
    val content = data["content"] as? JsonObject ?: return null
    fun value(key: String) = (content[key] as? JsonPrimitive)?.contentOrNull
    val type = value("notification_type") ?: value("type") ?: return null
    val title = value("title")?.takeIf(String::isNotBlank) ?: return null
    val body = value("body")?.takeIf(String::isNotBlank) ?: return null
    if (type != "recharge") return null
    val metadata = content["data"] as? JsonObject
    return value("notification_id") ?: (metadata?.get("order_id") as? JsonPrimitive)?.contentOrNull
        ?: (metadata?.get("orderId") as? JsonPrimitive)?.contentOrNull ?: "$type:$title:$body:${value("created_at").orEmpty()}"
}

/** 使用已存在的通知 data.order_id／orderId，通知 ID 与订单 ID 分别保留。 */
internal fun rechargeOrderId(envelope: JsonObject): String? {
    val publication = (envelope["pub"] ?: (envelope["push"] as? JsonObject)?.get("pub")) as? JsonObject ?: return null
    val data = publication["data"] as? JsonObject ?: return null
    val content = data["content"] as? JsonObject ?: return null
    val metadata = content["data"] as? JsonObject ?: return null
    return ((metadata["order_id"] ?: metadata["orderId"]) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
}

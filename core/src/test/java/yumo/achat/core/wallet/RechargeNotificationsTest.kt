package yumo.achat.core.wallet

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RechargeNotificationsTest {
    @Test
    fun `latest recharge notice is replayed to a late subscriber`() = runBlocking {
        val events = rechargeEventFlow()
        val notice = RechargeNotice(epoch = "epoch-1", key = "notice-1", orderId = "order-1")

        events.emit(notice)

        assertEquals(notice, events.first())
    }

    @Test
    fun `backend recharge payload exposes notification key and business order id`() {
        val envelope = Json.parseToJsonElement(
            """
            {
              "push": {
                "pub": {
                  "data": {
                    "message_type": "notification",
                    "content": {
                      "notification_type": "recharge",
                      "notification_id": "notice-1",
                      "title": "Recharge Successful",
                      "body": "Diamonds received",
                      "data": { "order_id": "order-123", "new_balance": 640 }
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        ).jsonObject

        assertEquals("notice-1", rechargeKey(envelope))
        assertEquals("order-123", rechargeOrderId(envelope))
    }

    @Test
    fun `non recharge notification is ignored`() {
        val envelope = Json.parseToJsonElement(
            """{"push":{"pub":{"data":{"message_type":"notification","content":{"notification_type":"task_complete","title":"Done","body":"Ready"}}}}}""",
        ).jsonObject

        assertNull(rechargeKey(envelope))
        assertNull(rechargeOrderId(envelope))
    }
}

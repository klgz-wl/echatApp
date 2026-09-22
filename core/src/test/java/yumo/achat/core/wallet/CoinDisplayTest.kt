package yumo.achat.core.wallet

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class CoinDisplayTest {
    private val product = CoinProduct("id", "diamond", "Coins", price = 99.0, coins = 500,
        bonus = 100, firstBuyPrice = 4.99, firstBuyBonus = 500, originalPrice = 9.99,
        googleProductId = "play", thirdPartyProductId = "fallback")
    @Test fun `首购显示专属赠币和首购价格`() {
        val value = product.copy(firstBuyPromotion = true)
        assertEquals(4.99, value.displayPrice!!, 0.0)
        assertEquals(500L, value.displayBonus)
        assertTrue(value.showBonus)
    }
    @Test fun `普通商品使用原价而不是price字段`() {
        assertEquals(9.99, product.displayPrice!!, 0.0)
        assertEquals(100L, product.displayBonus)
        assertEquals("play", product.playProductId)
        assertEquals("fallback", product.copy(googleProductId = null).playProductId)
    }
    @Test fun `首购缺省按参考回退并保留加零展示`() {
        val value = product.copy(firstBuyPromotion = true, firstBuyPrice = null, firstBuyBonus = null)
        assertEquals(9.99, value.displayPrice!!, 0.0)
        assertEquals(0L, value.displayBonus)
        assertTrue(value.showBonus)
        assertFalse(value.copy(originalPrice = null).canDisplay)
        assertFalse(product.copy(coins = null).canDisplay)
        assertFalse(product.copy(bonus = 0).showBonus)
    }
    @Test fun `仅识别参考协议中的充值通知并按订单键回退`() {
        fun parse(s: String) = rechargeKey(Json.parseToJsonElement(s).jsonObject)
        val content = """{"notification_type":"recharge","title":"Paid","body":"Credits added","data":{"order_id":"order"}}"""
        assertEquals("order", parse("""{"push":{"pub":{"data":{"message_type":"notification","content":$content}}}}"""))
        assertEquals("order", parse("""{"pub":{"data":{"message_type":"notification","content":$content}}}"""))
        assertNull(parse("""{"pub":{"data":{"message_type":"chat","content":$content}}}"""))
        assertNull(parse("""{"pub":{"data":{"message_type":"notification","content":{"type":"recharge"}}}}"""))
    }
}

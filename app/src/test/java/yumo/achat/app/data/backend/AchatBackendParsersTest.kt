package yumo.achat.app.data.backend

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AchatBackendParsersTest {
    @Test
    fun `parse store order snapshot`() {
        val order = AchatBackendParsers.parseStoreOrder(
            """
            {
              "code": 0,
              "data": {
                "order_id": "order-1",
                "order_number": "ORD-1",
                "product_id": "pack-100",
                "product_name": "100 Diamonds",
                "amount": 4.99,
                "currency": "USD",
                "status": "pending",
                "created_at": "2026-09-20T08:00:00Z",
                "obfuscated_account_id": "account-hash",
                "obfuscated_profile_id": "order-1"
              }
            }
            """.trimIndent(),
        )

        assertEquals("order-1", order.id)
        assertEquals("pack-100", order.productId)
        assertEquals(BigDecimal("4.99"), order.amount)
        assertEquals("account-hash", order.obfuscatedAccountId)
        assertEquals("order-1", order.obfuscatedProfileId)
    }

    @Test
    fun `parse official and third party payment initialization`() {
        val official = AchatBackendParsers.parsePaymentInitialization(
            """
            {
              "data": {
                "order_id": "order-1",
                "channel_type": "official",
                "channel_code": "google_play",
                "open_mode": "sdk",
                "sdk_params": {"product_id":"diamonds_100_first"}
              }
            }
            """.trimIndent(),
        )
        val thirdParty = AchatBackendParsers.parsePaymentInitialization(
            """
            {
              "data": {
                "order_id": "order-2",
                "channel_type": "third_party",
                "channel_code": "payu_web_us",
                "open_mode": "webview",
                "payment_url": "https://checkout.example/pay/2",
                "expires_at": "2026-09-20T08:10:00Z",
                "query_interval_seconds": 10,
                "max_query_seconds": 600
              }
            }
            """.trimIndent(),
        )

        assertEquals("diamonds_100_first", official.sdkProductId)
        assertEquals("payu_web_us", thirdParty.channelCode)
        assertEquals("https://checkout.example/pay/2", thirdParty.paymentUrl)
        assertEquals(600, thirdParty.maxQuerySeconds)
    }

    @Test
    fun `parse paid fulfilled payment status`() {
        val status = AchatBackendParsers.parsePaymentOrderStatus(
            """
            {
              "data": {
                "order_id": "order-1",
                "status": "paid",
                "payment_method": "payu_web_us",
                "fulfillment_status": "fulfilled",
                "paid_at": "2026-09-20T08:01:00Z",
                "verified_at": "2026-09-20T08:01:01Z",
                "fulfilled_at": "2026-09-20T08:01:02Z",
                "third_party_payment": {
                  "channel_code": "payu_web_us",
                  "status": "paid",
                  "open_mode": "webview",
                  "expires_at": "2026-09-20T08:10:00Z"
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("order-1", status.orderId)
        assertEquals("fulfilled", status.fulfillmentStatus)
        assertEquals("payu_web_us", status.thirdPartyPayment?.channelCode)
    }

    @Test
    fun `store catalog rejects a missing required price`() {
        assertThrows(IllegalStateException::class.java) {
            AchatBackendParsers.parseStoreCatalog(
                """
                {
                  "code": 0,
                  "data": {
                    "products": [{"id":"broken","type":"diamond","value":100,"currency":"USD"}],
                    "payment_providers": []
                  }
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `parse store catalog with decimal prices and backend ordering`() {
        val catalog = AchatBackendParsers.parseStoreCatalog(
            """
            {
              "code": 0,
              "data": {
                "payment_providers": [
                  {
                    "priority": 10,
                    "provider_code": "google_play",
                    "provider_name": "Google Play",
                    "supported_platforms": ["android"]
                  },
                  {
                    "priority": 20,
                    "provider_code": "payu_web_us",
                    "provider_name": "PayU",
                    "supported_platforms": ["android", "web"]
                  }
                ],
                "products": [
                  {
                    "id": "pack-100",
                    "name": "100 Diamonds",
                    "description": "Popular pack",
                    "type": "diamond",
                    "value": 100,
                    "bonus_value": 10,
                    "first_buy_bonus_value": 25,
                    "original_price": 9.99,
                    "price": 4.99,
                    "first_buy_price": 2.99,
                    "discount_rate": 0.5,
                    "first_buy_discount": 0.7,
                    "currency": "USD",
                    "is_promotion": true,
                    "is_first_buy_promotion": true,
                    "is_subscription": false,
                    "promotion_type": "first_buy",
                    "tags": "HOT",
                    "icon": "https://example.test/100.webp",
                    "third_party_product_id": "diamonds_100",
                    "sort_order": 20,
                    "vip_level": 0
                  },
                  {
                    "id": "pack-25",
                    "name": "25 Diamonds",
                    "description": "Starter pack",
                    "type": "diamond",
                    "value": 25,
                    "bonus_value": 0,
                    "original_price": 4.99,
                    "price": 1.99,
                    "currency": "USD",
                    "sort_order": 10
                  }
                ],
                "user_info": {
                  "current_diamond": 42,
                  "has_made_first_purchase": false,
                  "is_vip": false
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("pack-25", "pack-100"), catalog.products.map { it.id })
        assertEquals(BigDecimal("4.99"), catalog.products.last().price)
        assertEquals(BigDecimal("2.99"), catalog.products.last().firstBuyPrice)
        assertEquals(listOf("payu_web_us", "google_play"), catalog.paymentProviders.map { it.code })
        assertEquals(42, catalog.userInfo?.currentDiamond)
    }

    @Test
    fun `parse auth session from api envelope`() {
        val session = AchatBackendParsers.parseAuthSession(
            """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "user_id": "user-1",
                "token": "access-token",
                "refresh_token": "refresh-token",
                "session_id": "session-1",
                "is_anonymous": true
              }
            }
            """.trimIndent(),
        )

        assertEquals("user-1", session.userId)
        assertEquals("access-token", session.token)
        assertEquals("refresh-token", session.refreshToken)
        assertEquals("session-1", session.sessionId)
        assertEquals(true, session.isAnonymous)
    }

    @Test
    fun `parse auth session when session id is omitted`() {
        val session = AchatBackendParsers.parseAuthSession(
            """
            {
              "code": 0,
              "message": "success",
              "data": {
                "user_id": "user-1",
                "token": "access-token",
                "refresh_token": "refresh-token",
                "is_anonymous": true
              }
            }
            """.trimIndent(),
        )

        assertEquals("user-1", session.userId)
        assertEquals("access-token", session.token)
        assertEquals("refresh-token", session.refreshToken)
        assertEquals("", session.sessionId)
        assertEquals(true, session.isAnonymous)
    }

    @Test
    fun `parse templates sorted by hot score`() {
        val templates = AchatBackendParsers.parseTemplates(
            """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "items": [
                  {
                    "id": "cold",
                    "name": "Cold",
                    "file_url": "https://example.test/cold.webp",
                    "mime_type": "image/webp",
                    "width": 720,
                    "height": 1280,
                    "duration": 4,
                    "hot_score": 12,
                    "prices": { "fast": 11, "quality": 22 }
                  },
                  {
                    "id": "hot",
                    "name": "Hot",
                    "file_url": "https://example.test/hot.webp",
                    "mime_type": "video/mp4",
                    "width": 720,
                    "height": 1280,
                    "duration": 5,
                    "hot_score": 99,
                    "prices": { "fast": 9 }
                  }
                ],
                "total": 2,
                "page": 1,
                "page_size": 20
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("hot", "cold"), templates.map { it.id })
        assertEquals("Hot", templates.first().name)
        assertEquals("video/mp4", templates.first().mimeType)
        assertEquals(9, templates.first().fastPrice)
        assertEquals(null, templates.first().qualityPrice)
    }

    @Test
    fun `parse categories sorted by sort order`() {
        val categories = AchatBackendParsers.parseCategories(
            """
            {
              "code": 0,
              "message": "ok",
              "data": [
                { "id": "fashion", "name": "Fashion", "sort_order": 20 },
                { "id": "portrait", "name": "Portrait", "sort_order": 10 }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("portrait", "fashion"), categories.map { it.id })
        assertEquals("Portrait", categories.first().name)
        assertEquals(10, categories.first().sortOrder)
    }

    @Test
    fun `parse user summary with display fallback`() {
        val profile = AchatBackendParsers.parseUserProfile(
            """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "id": "3095609813",
                "username": "quiet-user",
                "nickname": "Quiet wanderer",
                "avatar": "https://example.test/avatar.webp"
              }
            }
            """.trimIndent(),
        )
        val currency = AchatBackendParsers.parseUserCurrency(
            """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "user_id": "3095609813",
                "diamond_balance": 42,
                "diamond_earned": 100,
                "diamond_spent": 58
              }
            }
            """.trimIndent(),
        )

        assertEquals("Quiet wanderer", profile.displayName)
        assertEquals("3095609813", profile.id)
        assertEquals(42, currency.diamondBalance)
    }

    @Test
    fun `parse uploaded visual resource`() {
        val resource = AchatBackendParsers.parseVisualResource(
            """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "id": "resource-1",
                "task_id": "",
                "resource_type": "upload",
                "modality": "image",
                "url": "https://example.test/upload.webp",
                "thumbnail_url": "https://example.test/thumb.webp",
                "mime_type": "image/webp",
                "width": 720,
                "height": 1280,
                "duration": 0
              }
            }
            """.trimIndent(),
        )

        assertEquals("resource-1", resource.id)
        assertEquals("upload", resource.resourceType)
        assertEquals("image", resource.modality)
        assertEquals("https://example.test/upload.webp", resource.url)
        assertEquals("https://example.test/thumb.webp", resource.thumbnailUrl)
    }

    @Test
    fun `parse generated resource history`() {
        val resources = AchatBackendParsers.parseVisualResources(
            """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "items": [
                  {
                    "id": "generated-1",
                    "task_id": "task-1",
                    "resource_type": "generated",
                    "modality": "image",
                    "template_id": "template-1",
                    "template_name": "Rose portrait",
                    "url": "https://example.test/generated.webp",
                    "thumbnail_url": "https://example.test/thumb.webp",
                    "mime_type": "image/webp",
                    "width": 720,
                    "height": 1280,
                    "duration": 0,
                    "created_at": "2026-09-16T08:00:00Z",
                    "expires_at": "2026-09-23T08:00:00Z"
                  }
                ],
                "page": 1,
                "page_size": 20,
                "total": 1
              }
            }
            """.trimIndent(),
        )

        assertEquals(1, resources.size)
        assertEquals("generated-1", resources.first().id)
        assertEquals("task-1", resources.first().taskId)
        assertEquals("Rose portrait", resources.first().templateName)
        assertEquals("https://example.test/generated.webp", resources.first().url)
        assertEquals("2026-09-16T08:00:00Z", resources.first().createdAt)
    }

    @Test
    fun `parse visual generation task`() {
        val task = AchatBackendParsers.parseVisualGenerationTask(
            """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "task_id": "task-1",
                "status": "processing",
                "modality": "video",
                "quality": "fast",
                "template_id": "template-1",
                "diamond_cost": 22,
                "estimated_poll_interval_seconds": 3,
                "refunded": false
              }
            }
            """.trimIndent(),
        )

        assertEquals("task-1", task.taskId)
        assertEquals("processing", task.status)
        assertEquals("video", task.modality)
        assertEquals("fast", task.quality)
        assertEquals("template-1", task.templateId)
        assertEquals(22, task.diamondCost)
        assertEquals(3, task.estimatedPollIntervalSeconds)
    }
}

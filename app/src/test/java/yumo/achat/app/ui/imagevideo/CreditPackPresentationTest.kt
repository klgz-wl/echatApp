package yumo.achat.app.ui.imagevideo

import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import yumo.achat.core.backend.StoreProduct
import yumo.achat.core.backend.StoreUserInfo

class CreditPackPresentationTest {
    @Test
    fun `first buy presentation uses first buy price and combined bonus`() {
        val pack = storeProduct().toCreditPackPresentation(
            userInfo = StoreUserInfo(
                currentDiamond = 42,
                hasMadeFirstPurchase = false,
                isVip = false,
            ),
            displayIndex = 0,
            locale = Locale.US,
        )

        assertEquals("pack-100", pack.id)
        assertEquals(100, pack.credits)
        assertEquals(35, pack.bonus)
        assertEquals("$2.99", pack.price)
        assertEquals("$9.99", pack.originalPrice)
        assertEquals("Valid for 60 days", pack.description)
        assertEquals("HOT", pack.badgeText)
        assertEquals("TIER // 01", pack.tierLabel)
    }

    @Test
    fun `returning buyer presentation uses regular promotion`() {
        val pack = storeProduct().toCreditPackPresentation(
            userInfo = StoreUserInfo(
                currentDiamond = 42,
                hasMadeFirstPurchase = true,
                isVip = false,
            ),
            displayIndex = 1,
            locale = Locale.US,
        )

        assertEquals(10, pack.bonus)
        assertEquals("$4.99", pack.price)
        assertEquals("$9.99", pack.originalPrice)
        assertEquals("TIER // 02", pack.tierLabel)
    }

    @Test
    fun `comparison price does not depend on promotion flag`() {
        val pack = storeProduct().copy(isPromotion = false).toCreditPackPresentation(
            userInfo = null,
            displayIndex = 0,
            locale = Locale.US,
        )

        assertEquals("$9.99", pack.originalPrice)
        assertEquals("HOT", pack.badgeText)
    }

    @Test
    fun `money formatting honors backend currency fraction digits`() {
        assertEquals("¥123", formatStoreMoney(BigDecimal("123"), "JPY", Locale.US))
        assertEquals("KWD1.235", formatStoreMoney(BigDecimal("1.2345"), "KWD", Locale.US))
        assertEquals("ABC 1.20", formatStoreMoney(BigDecimal("1.2"), "ABC", Locale.US))
    }

    @Test
    fun `first buy zero price falls back to regular price`() {
        val pack = storeProduct().copy(firstBuyPrice = BigDecimal.ZERO).toCreditPackPresentation(
            userInfo = StoreUserInfo(0, hasMadeFirstPurchase = false, isVip = false),
            displayIndex = 0,
            locale = Locale.US,
        )

        assertEquals("$4.99", pack.price)
        assertNull(pack.badgeText?.takeIf { it.isBlank() })
    }

    @Test
    fun `missing backend validity hides subtitle instead of deriving display tier days`() {
        val blankDescriptionPack = storeProduct().copy(description = "").toCreditPackPresentation(
            userInfo = null,
            displayIndex = 1,
            locale = Locale.US,
        )
        val localizedDescriptionPack = storeProduct().copy(description = "100金币").toCreditPackPresentation(
            userInfo = null,
            displayIndex = 2,
            locale = Locale.US,
        )

        assertEquals("", blankDescriptionPack.description)
        assertEquals("", localizedDescriptionPack.description)
    }

    @Test
    fun `selected pack expands while other packs remain compact`() {
        assertEquals(164, creditPackHeightDp(selected = true))
        assertEquals(126, creditPackHeightDp(selected = false))
    }

    private fun storeProduct() = StoreProduct(
        id = "pack-100",
        name = "100 Diamonds",
        description = "Valid for 60 days",
        type = "diamond",
        value = 100,
        bonusValue = 10,
        firstBuyBonusValue = 25,
        currency = "USD",
        originalPrice = BigDecimal("9.99"),
        price = BigDecimal("4.99"),
        firstBuyPrice = BigDecimal("2.99"),
        discountRate = BigDecimal("0.5"),
        firstBuyDiscount = BigDecimal("0.7"),
        icon = "https://example.test/100.webp",
        isFirstBuyPromotion = true,
        isPromotion = true,
        isSubscription = false,
        promotionType = "first_buy",
        sortOrder = 20,
        tags = "HOT",
        thirdPartyProductId = "diamonds_100",
        googleProductId = "play.diamonds_100",
        vipLevel = 0,
    )
}

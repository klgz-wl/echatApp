package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import yumo.achat.core.backend.StoreCatalog
import yumo.achat.core.backend.StoreProduct
import yumo.achat.core.backend.StoreUserInfo
import yumo.achat.app.ui.theme.AchatTheme
import yumo.achat.core.payment.InitializedPayment
import yumo.achat.core.payment.PaymentRecord
import yumo.achat.core.payment.PaymentSdkParams
import yumo.achat.core.payment.PaymentStage

class TopUpCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectedPackUsesLiveCatalogAndSelectionCanMove() {
        var selectedProductId by mutableStateOf<String?>("pack-200")
        var prepareCount = 0
        composeRule.setContent {
            AchatTheme {
                TopUpScreen(
                    selectedNavigation = 2,
                    selectedProductId = selectedProductId,
                    state = TopUpUiState(catalog = catalog()),
                    purchaseState = TopUpPurchaseState.Idle,
                    onProductSelect = { selectedProductId = it },
                    onPreparePayment = { prepareCount += 1 },
                    onRetry = {},
                    onNavigationSelect = {},
                )
            }
        }

        composeRule.onAllNodesWithText("DMDS").assertCountEquals(2)
        composeRule.onNodeWithText("Valid for 90 days").assertIsDisplayed()
        composeRule.onNodeWithText("Valid for 60 days").assertIsDisplayed()
        composeRule.onNodeWithText("LOCKED PROTOCOL").assertIsDisplayed()
        composeRule.onNodeWithText("TIER // 02").assertIsDisplayed()
        composeRule.onNodeWithText("100").performClick()

        assertEquals("pack-100", selectedProductId)
        composeRule.onNodeWithText("TIER // 01").assertIsDisplayed()
        composeRule.onAllNodesWithText("TIER // 02").assertCountEquals(0)
        composeRule.onNodeWithText("CONTINUE").performClick()
        assertEquals(1, prepareCount)
    }

    @Test
    fun loadingErrorAndEmptyCatalogHaveDedicatedStates() {
        var retryCount = 0
        var state by mutableStateOf(TopUpUiState(isLoading = true))
        composeRule.setContent {
            AchatTheme {
                TopUpScreen(
                    selectedNavigation = 2,
                    selectedProductId = null,
                    state = state,
                    purchaseState = TopUpPurchaseState.Idle,
                    onProductSelect = {},
                    onPreparePayment = {},
                    onRetry = { retryCount += 1 },
                    onNavigationSelect = {},
                )
            }
        }
        composeRule.onNodeWithText("Loading packs...").assertIsDisplayed()
        composeRule.onAllNodesWithText("200").assertCountEquals(0)

        state = TopUpUiState(errorMessage = "Catalog unavailable")
        composeRule.onNodeWithText("Catalog unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retryCount)

        state = TopUpUiState(catalog = StoreCatalog(emptyList(), emptyList(), null))
        composeRule.onNodeWithText("No packs available").assertIsDisplayed()
    }

    @Test
    fun missingBackendValidityDoesNotRenderValiditySubtitle() {
        composeRule.setContent {
            AchatTheme {
                TopUpScreen(
                    selectedNavigation = 2,
                    selectedProductId = "pack-200",
                    state = TopUpUiState(
                        catalog = StoreCatalog(
                            products = listOf(
                                product("pack-200", 200, 1, "39.99", description = ""),
                                product("pack-100", 100, 2, "19.99", description = "100金币"),
                            ),
                            paymentProviders = emptyList(),
                            userInfo = StoreUserInfo(88, hasMadeFirstPurchase = true, isVip = false),
                        ),
                    ),
                    purchaseState = TopUpPurchaseState.Idle,
                    onProductSelect = {},
                    onPreparePayment = {},
                    onRetry = {},
                    onNavigationSelect = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Valid for 90 days").assertCountEquals(0)
        composeRule.onAllNodesWithText("Valid for 60 days").assertCountEquals(0)
    }

    @Test
    fun preparedPaymentRoutesAreVisibleWithoutOpeningCheckout() {
        var purchaseState by mutableStateOf<TopUpPurchaseState>(
            TopUpPurchaseState.OfficialReady(
                record = paymentRecord("pack-200", "order-1", official = true),
            ),
        )
        composeRule.setContent {
            AchatTheme {
                TopUpScreen(
                    selectedNavigation = 2,
                    selectedProductId = "pack-200",
                    state = TopUpUiState(catalog = catalog()),
                    purchaseState = purchaseState,
                    onProductSelect = {},
                    onPreparePayment = {},
                    onRetry = {},
                    onNavigationSelect = {},
                )
            }
        }

        composeRule.onNodeWithText("Google Play ready").assertIsDisplayed()
        composeRule.onNodeWithText("Checkout opens in phase 3").assertIsDisplayed()

        purchaseState = TopUpPurchaseState.ThirdPartyReady(
            record = paymentRecord("pack-200", "order-2", official = false),
        )
        composeRule.onNodeWithText("Checkout ready").assertIsDisplayed()
        composeRule.onNodeWithText("payu_web_us").assertIsDisplayed()
    }

    private fun catalog() = StoreCatalog(
        products = listOf(
            product("pack-200", 200, 1, "39.99"),
            product("pack-100", 100, 2, "19.99"),
        ),
        paymentProviders = emptyList(),
        userInfo = StoreUserInfo(88, hasMadeFirstPurchase = true, isVip = false),
    )

    private fun product(
        id: String,
        value: Int,
        sortOrder: Int,
        price: String,
        description: String = if (value == 200) "Valid for 90 days" else "Valid for 60 days",
    ) = StoreProduct(
        id = id,
        name = "$value Diamonds",
        description = description,
        type = "diamond",
        value = value,
        bonusValue = 0,
        firstBuyBonusValue = 0,
        currency = "USD",
        originalPrice = BigDecimal.ZERO,
        price = BigDecimal(price),
        firstBuyPrice = BigDecimal.ZERO,
        discountRate = BigDecimal.ZERO,
        firstBuyDiscount = BigDecimal.ZERO,
        icon = "",
        isFirstBuyPromotion = false,
        isPromotion = false,
        isSubscription = false,
        promotionType = "",
        sortOrder = sortOrder,
        tags = "",
        thirdPartyProductId = "sku-$value",
        googleProductId = "",
        vipLevel = 0,
    )

    private fun paymentRecord(productId: String, orderId: String, official: Boolean) = PaymentRecord(
        key = "key-$orderId",
        userId = "user",
        productId = productId,
        source = "main",
        orderId = orderId,
        initialized = if (official) {
            InitializedPayment(orderId, "official", "google_play", "sdk", PaymentSdkParams("diamonds_200"))
        } else {
            InitializedPayment(
                orderId, "third_party", "payu_web_us", "webview",
                paymentUrl = "https://checkout.example/pay/2",
                expiresAt = "2099-01-01T00:00:00Z",
            )
        },
        stage = if (official) PaymentStage.OFFICIAL_READY else PaymentStage.CHECKOUT,
    )
}

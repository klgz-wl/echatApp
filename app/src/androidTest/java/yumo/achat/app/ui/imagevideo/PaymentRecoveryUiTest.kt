package yumo.achat.app.ui.imagevideo

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import yumo.achat.app.R
import yumo.achat.app.ui.theme.AchatTheme
import yumo.achat.core.backend.StoreCatalog
import yumo.achat.core.backend.StoreProduct
import yumo.achat.core.billing.CoinPurchaseState
import yumo.achat.core.payment.InitializedPayment
import yumo.achat.core.payment.PaymentRecord
import yumo.achat.core.payment.PaymentStage
import yumo.achat.core.payment.PaymentViewState

class PaymentRecoveryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recoveredRetiredProductKeepsSnapshotAndExplicitRetry() {
        verifyRecoveredOrder(catalog())
    }

    @Test fun recoveredOrderKeepsRetryWithoutAnyCatalog() {
        verifyRecoveredOrder(null)
    }

    @Test fun closedRetiredOrderKeepsSameOrderRetry() {
        verifyRetiredOrderRetry(PaymentStage.CLOSED)
    }

    @Test fun timedOutRetiredOrderKeepsSameOrderRetry() {
        verifyRetiredOrderRetry(PaymentStage.TIMED_OUT)
    }

    private fun verifyRetiredOrderRetry(stage: PaymentStage) {
        val original = record().copy(productId = "retired-product", stage = stage)
        val view = PaymentViewState(epoch = "session", record = original, checkoutVisible = false)
        var retriedKey: String? = null
        compose.setContent {
            AchatTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF070812))) {
                    TopUpScreen(selectedNavigation = 2, selectedProductId = original.productId,
                        productSelectionEnabled = false, state = TopUpUiState(),
                        purchaseState = TopUpPurchaseState.ThirdPartyReady(original),
                        checkoutState = view.toTopUpCheckoutState(CoinPurchaseState()),
                        onProductSelect = {}, onPreparePayment = { retriedKey = original.key },
                        onRetry = {}, onNavigationSelect = {})
                }
            }
        }
        compose.onNodeWithText(text(R.string.top_up_try_again)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(original.key, retriedKey) }
    }

    private fun verifyRecoveredOrder(availableCatalog: StoreCatalog?) {
        val recovered = PaymentViewState(epoch = "session", record = PaymentRecord(
            "original-request", "user", "retired-product", "restored", orderId = "original-order",
            stage = PaymentStage.UNCERTAIN, recoveryRequired = true, quotePrice = 7.25, quoteCurrency = "USD"))
        var retries = 0
        compose.setContent {
            AchatTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF070812))) {
                TopUpScreen(selectedNavigation = 2, selectedProductId = "retired-product", productSelectionEnabled = false,
                    state = TopUpUiState(catalog = availableCatalog),
                    purchaseState = TopUpPurchaseState.Error("retired-product", "", R.string.top_up_recovered_order),
                    checkoutState = recovered.toTopUpCheckoutState(CoinPurchaseState()),
                    onProductSelect = {}, onPreparePayment = { retries++ }, onRetry = {}, onNavigationSelect = {})
                }
            }
        }
        compose.onNodeWithText(text(R.string.top_up_recovered_order), substring = true).assertIsDisplayed()
        compose.onNodeWithText("retired-product: USD 7.25", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, retries) }
        compose.onNodeWithText(text(R.string.top_up_try_again)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        screenshot("payment-server-recovery.png")
    }

    @Test fun networkFailureHasVisibleSameOrderRetry() {
        var retries = 0
        var selections = 0
        compose.setContent {
            AchatTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF070812))) {
                TopUpScreen(selectedNavigation = 2, selectedProductId = "pack", productSelectionEnabled = false,
                    state = TopUpUiState(catalog = catalog()),
                    purchaseState = TopUpPurchaseState.ThirdPartyReady(record()),
                    checkoutState = TopUpCheckoutState.Failed("", recoverable = true,
                        messageRes = R.string.top_up_payment_network_error),
                    onProductSelect = { selections++ }, onPreparePayment = { retries++ },
                    onRetry = {}, onNavigationSelect = {})
                }
            }
        }
        compose.onNodeWithText(text(R.string.top_up_payment_network_error)).assertIsDisplayed()
        compose.onNodeWithText("200").performClick()
        compose.onNodeWithText(text(R.string.top_up_try_again)).performClick()
        compose.runOnIdle { assertEquals(0, selections); assertEquals(1, retries) }
        screenshot("payment-network-retry.png")
    }

    @Test fun cardDeclineShowsCardRecoveryAction() {
        compose.setContent {
            AchatTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF070812))) {
                TopUpScreen(selectedNavigation = 2, selectedProductId = "pack",
                    state = TopUpUiState(catalog = catalog()),
                    purchaseState = TopUpPurchaseState.ThirdPartyReady(record().copy(stage = PaymentStage.FAILED)),
                    checkoutState = TopUpCheckoutState.Failed("", messageRes = R.string.top_up_card_declined,
                        actionRes = R.string.top_up_use_another_card),
                    onProductSelect = {}, onPreparePayment = {}, onRetry = {}, onNavigationSelect = {})
                }
            }
        }
        compose.onNodeWithText(text(R.string.top_up_card_declined)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.top_up_use_another_card)).assertIsDisplayed()
        screenshot("payment-card-recovery.png")
    }

    @Test fun restoredCheckoutHasARecoveryButton() {
        val restored = record().copy(openedAt = 123L)
        val state = PaymentViewState(epoch = "session", record = restored, checkoutVisible = false)
        var retriedKey: String? = null
        compose.setContent {
            AchatTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF070812))) {
                    TopUpScreen(selectedNavigation = 2, selectedProductId = "pack", productSelectionEnabled = false,
                        state = TopUpUiState(catalog = catalog()),
                        purchaseState = TopUpPurchaseState.ThirdPartyReady(restored),
                        checkoutState = state.toTopUpCheckoutState(CoinPurchaseState()),
                        onProductSelect = {}, onPreparePayment = { retriedKey = restored.key },
                        onRetry = {}, onNavigationSelect = {})
                }
            }
        }
        compose.onNodeWithText(text(R.string.top_up_payment_unconfirmed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.top_up_try_again)).performClick()
        compose.runOnIdle { assertEquals(restored.key, retriedKey) }
        screenshot("payment-restored-retry.png")
    }

    @Test fun expiredCheckoutDoesNotCreateAWebView() {
        var expired = 0
        compose.setContent {
            AchatTheme {
                ThirdPartyCheckoutOverlay(
                    route = TopUpPurchaseState.ThirdPartyReady(record().copy(initialized = record().initialized!!.copy(
                        expiresAt = "2000-01-01T00:00:00Z"))),
                    onLoaded = { _, _ -> }, onError = { _, _ -> }, onClose = {}, onRetry = {},
                    onExpired = { expired++ },
                )
            }
        }
        compose.onNodeWithText(text(R.string.top_up_payment_timeout)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, expired) }
        onView(isAssignableFrom(WebView::class.java)).check(doesNotExist())
        screenshot("payment-expired-checkout.png")
    }

    private fun record() = PaymentRecord("request", "user", "pack", "manual", orderId = "order",
        initialized = InitializedPayment("order", "third_party", "payu_web", "webview",
            paymentUrl = "https://checkout.invalid/order", expiresAt = "2099-01-01T00:00:00Z"),
        stage = PaymentStage.CHECKOUT)

    private fun catalog() = StoreCatalog(products = listOf(StoreProduct(
        id = "pack", name = "200 Diamonds", description = "", type = "diamond", value = 200,
        bonusValue = 0, firstBuyBonusValue = 0, currency = "USD", originalPrice = BigDecimal("19.99"),
        price = BigDecimal("19.99"), firstBuyPrice = BigDecimal.ZERO, discountRate = BigDecimal.ZERO,
        firstBuyDiscount = BigDecimal.ZERO, icon = "", isFirstBuyPromotion = false, isPromotion = false,
        isSubscription = false, promotionType = "", sortOrder = 1, tags = "", thirdPartyProductId = "sku",
        googleProductId = "", vipLevel = 0)), paymentProviders = emptyList(), userInfo = null)

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun screenshot(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val folder = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(folder, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

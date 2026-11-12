package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VipBalanceActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun vipPrecedesBalanceAndKeepsSeparateActionsForBothMembershipStates() {
        val membership = mutableStateOf<Boolean?>(true)
        var vipClicks = 0
        var balanceClicks = 0
        compose.setContent {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                VipBalanceActions(membership.value, 12345, { vipClicks++ }, { balanceClicks++ })
            }
        }
        val vip = compose.onNodeWithText("VIP")
        val balance = compose.onNodeWithText("12345")
        assertTrue(vip.fetchSemanticsNode().boundsInRoot.right < balance.fetchSemanticsNode().boundsInRoot.left)
        vip.performClick()
        balance.performClick()
        compose.runOnIdle { assertEquals(1, vipClicks); assertEquals(1, balanceClicks); membership.value = false }
        vip.assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, vipClicks); membership.value = null }
        vip.assertDoesNotExist()
        balance.assertIsDisplayed()
    }
}

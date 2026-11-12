package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import yumo.achat.app.ui.theme.AchatTheme

class TemplateVipBadgeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun vipContentShowsTemplateBadge() {
        compose.setContent { AchatTheme { TemplateVipBadge(vip = true) } }
        compose.onNodeWithContentDescription("VIP-only template").assertIsDisplayed()
    }
    @Test fun ordinaryContentHasNoVipBadge() {
        compose.setContent { AchatTheme { TemplateVipBadge(vip = false) } }
        compose.onNodeWithContentDescription("VIP-only template").assertDoesNotExist()
    }
}

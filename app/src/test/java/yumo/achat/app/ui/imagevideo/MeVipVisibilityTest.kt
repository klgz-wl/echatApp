package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeVipVisibilityTest {
    @Test fun badgeClickRoutesBothVipAndNonVipToSubscription() {
        assertEquals(VipBadgeTarget.Subscription, vipBadgeTarget(true))
        assertEquals(VipBadgeTarget.Subscription, vipBadgeTarget(false))
        assertNull(vipBadgeTarget(null))
    }

    @Test fun membershipSelectsTheMatchingDesign() {
        assertEquals(MeVipBadgeStyle.NonVip, meVipBadgeStyle(false))
        assertEquals(MeVipBadgeStyle.Vip, meVipBadgeStyle(true))
        assertNull(meVipBadgeStyle(null))
    }
}

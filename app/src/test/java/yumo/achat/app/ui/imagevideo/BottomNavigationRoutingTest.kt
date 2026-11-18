package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavigationRoutingTest {
    @Test fun `cancel top up handles both normal section and episode return targets`() {
        val home=BottomNavigationRoute(0,ImageToVideoDestination.Templates)
        val drama=BottomNavigationRoute(4,ImageToVideoDestination.ShortDramaPlayer)
        assertEquals(true,shouldHandleTopUpBack(ImageToVideoDestination.Templates,2,home))
        assertEquals(true,shouldHandleTopUpBack(ImageToVideoDestination.Templates,2,drama))
        assertEquals(false,shouldHandleTopUpBack(ImageToVideoDestination.Templates,2,null))
        assertEquals(false,shouldHandleTopUpBack(ImageToVideoDestination.Templates,4,home))
    }
    @Test fun `saved top up return supports short and video navigation indices`() {
        assertEquals(BottomNavigationRoute(4,ImageToVideoDestination.ShortDramaPlayer),restoreTopUpReturnRoute(4,"ShortDramaPlayer"))
        assertEquals(BottomNavigationRoute(5,ImageToVideoDestination.UploadPhoto),restoreTopUpReturnRoute(5,"UploadPhoto"))
        assertEquals(null,restoreTopUpReturnRoute(-1,"Templates"))
        assertEquals(null,restoreTopUpReturnRoute(4,"invalid"))
    }
    @Test fun `balance purchase returns to original section after success`() {
        assertEquals(BottomNavigationRoute(0,ImageToVideoDestination.Templates),balancePurchaseReturnTarget(0,ImageToVideoDestination.Templates))
        assertEquals(BottomNavigationRoute(4,ImageToVideoDestination.Templates),balancePurchaseReturnTarget(4,ImageToVideoDestination.Templates))
        assertEquals(null,balancePurchaseReturnTarget(2,ImageToVideoDestination.Templates))
    }
    @Test
    fun `every bottom navigation item returns to the root container`() {
        listOf(0, 4, 5, 1).forEach { navigationIndex ->
            val route = routeFromBottomNavigation(navigationIndex)

            assertEquals(navigationIndex, route.selectedNavigation)
            assertEquals(ImageToVideoDestination.Templates, route.destination)
        }
    }

    @Test
    fun `reselecting top up requests a fresh catalog load`() {
        assertEquals(true, shouldRefreshTopUp(currentNavigation = 2, selectedNavigation = 2))
        assertEquals(false, shouldRefreshTopUp(currentNavigation = 0, selectedNavigation = 3))
    }

    @Test
    fun `diamond balance badge routes to top up`() {
        val route = routeFromBalanceBadgeClick()

        assertEquals(2, route.selectedNavigation)
        assertEquals(ImageToVideoDestination.Templates, route.destination)
    }

    @Test
    fun `top up analytics preserves balance and generation entry sources`() {
        assertEquals("video_balance", balanceTopUpEntrySource(0))
        assertEquals("image_balance", balanceTopUpEntrySource(1))
        assertEquals("profile_purchase", balanceTopUpEntrySource(3))
        assertEquals(
            "generation",
            balanceTopUpEntrySource(1, ImageToVideoDestination.UploadPhoto),
        )
        assertEquals("generation", insufficientBalanceTopUpEntrySource())
    }

    @Test
    fun `manual top up entry does not create a return route`() {
        assertEquals(null, topUpReturnTargetForManualEntry())
    }

    @Test
    fun `insufficient balance top up entry returns to upload photo after payment success`() {
        val returnTarget = topUpReturnTargetForInsufficientBalance(
            selectedNavigation = 1,
            destination = ImageToVideoDestination.UploadPhoto,
        )

        assertEquals(
            BottomNavigationRoute(
                selectedNavigation = 1,
                destination = ImageToVideoDestination.UploadPhoto,
            ),
            returnTarget,
        )
        assertEquals(
            BottomNavigationRoute(
                selectedNavigation = 1,
                destination = ImageToVideoDestination.UploadPhoto,
            ),
            routeAfterTopUpSuccess(returnTarget),
        )
        assertEquals(null, routeAfterTopUpSuccess(null))
    }

    @Test
    fun `successful generation task routes to my tasks`() {
        val route = routeAfterGenerationTaskCreated(selectedNavigation = 1)

        assertEquals(
            BottomNavigationRoute(
                selectedNavigation = 1,
                destination = ImageToVideoDestination.MyTasks,
            ),
            route,
        )
    }
}

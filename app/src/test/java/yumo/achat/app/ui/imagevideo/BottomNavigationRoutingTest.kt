package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavigationRoutingTest {
    @Test
    fun `every bottom navigation item returns to the root container`() {
        (0..3).forEach { navigationIndex ->
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
}

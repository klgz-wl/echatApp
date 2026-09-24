package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Test

class PageAnalyticsMappingTest {
    @Test
    fun `template destinations map navigation to contract pages`() {
        assertEquals("video", analyticsPageName(ImageToVideoDestination.Templates, 0, false))
        assertEquals("image", analyticsPageName(ImageToVideoDestination.Templates, 1, false))
        assertEquals("purchase", analyticsPageName(ImageToVideoDestination.Templates, 2, false))
        assertEquals("profile", analyticsPageName(ImageToVideoDestination.Templates, 3, false))
    }

    @Test
    fun `secondary destinations map to stable pages`() {
        assertEquals("generation", analyticsPageName(ImageToVideoDestination.UploadPhoto, 0, false))
        assertEquals("records", analyticsPageName(ImageToVideoDestination.MyTasks, 0, false))
        assertEquals("media", analyticsPageName(ImageToVideoDestination.MyTasks, 0, true))
        assertEquals("settings", analyticsPageName(ImageToVideoDestination.EditName, 3, false))
    }
}

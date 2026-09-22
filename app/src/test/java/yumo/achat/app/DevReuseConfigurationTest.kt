package yumo.achat.app

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevReuseConfigurationTest {
    @Test
    fun `prod reuses the complete dev configuration`() {
        val workingDirectory = System.getProperty("user.dir") ?: error("Missing test working directory")
        val root = requireNotNull(File(workingDirectory).parentFile)
        val app = properties(File(root, "config/app.properties"))
        val devFile = File(root, "config/dev.properties")
        val prodFile = File(root, "config/prod.properties")

        assertEquals("DEV_REUSE", app.getProperty("kit.prod.mode").orEmpty())
        assertTrue("dev configuration is required", devFile.isFile)
        assertTrue("prod configuration is required", prodFile.isFile)
        assertEquals(devFile.readBytes().toList(), prodFile.readBytes().toList())
    }

    private fun properties(file: File) = Properties().apply {
        file.inputStream().use(::load)
    }
}

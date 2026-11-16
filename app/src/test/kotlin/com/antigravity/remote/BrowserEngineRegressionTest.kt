package com.antigravity.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BrowserEngineRegressionTest {

    @Test
    fun testGeckoViewPresentOnRuntimeClasspathWithoutCustomTabs() {
        val geckoViewClass = Class.forName("org.mozilla.geckoview.GeckoView")
        val geckoSessionClass = Class.forName("org.mozilla.geckoview.GeckoSession")
        assertNotNull(geckoViewClass)
        assertNotNull(geckoSessionClass)

        val customTabsMissing = try {
            Class.forName("androidx.browser.customtabs.CustomTabsIntent")
            false
        } catch (_: ClassNotFoundException) {
            true
        }
        assertTrue("CustomTabsIntent must not be bundled in dependencies", customTabsMissing)
    }

    @Test
    fun testApplicationSourcesAndLayoutDoNotReferenceSystemWebView() {
        val moduleDir = resolveAppModuleDir()
        val mainDir = File(moduleDir, "src/main")
        assertTrue("Expected app/src/main directory at ${mainDir.absolutePath}", mainDir.isDirectory)

        val sourceFiles = mainDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .toList()
        assertTrue("Expected compiled Kotlin and XML files under src/main", sourceFiles.isNotEmpty())

        for (file in sourceFiles) {
            val content = file.readText()
            assertFalse("Forbidden android.webkit.WebView in ${file.name}", content.contains("android.webkit.WebView"))
            assertFalse("Forbidden CustomTabsIntent in ${file.name}", content.contains("CustomTabsIntent"))
        }

        val layoutFile = File(mainDir, "res/layout/activity_main.xml")
        assertTrue("activity_main.xml missing", layoutFile.isFile)
        assertTrue(
            "activity_main.xml must embed org.mozilla.geckoview.GeckoView",
            layoutFile.readText().contains("org.mozilla.geckoview.GeckoView"),
        )
    }

    private fun resolveAppModuleDir(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "src/main").isDirectory) cwd else File(cwd, "app")
    }
}

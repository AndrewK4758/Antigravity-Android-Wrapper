package com.antigravity.remote

import com.antigravity.remote.policy.IntentUriValidator
import com.antigravity.remote.policy.UriValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompatibilityContractTest {

    @Test
    fun c1_intent_filter_fixtures_round_trip_identically_and_manifest_matches_contract() {
        val fixtures = listOf(
            "https://antigravity.google.com" to "https://antigravity.google.com/",
            "https://antigravity.google.com/" to "https://antigravity.google.com/",
            "https://antigravity.google.com/r/demo-inst" to "https://antigravity.google.com/r/demo-inst",
            "https://antigravity.google.com/r/dev-box?p=c%2Fabc123" to "https://antigravity.google.com/r/dev-box?p=c%2Fabc123",
            "https://antigravity.google.com/r/box-1?p=c%2Fconv-2&tab=logs" to "https://antigravity.google.com/r/box-1?p=c%2Fconv-2&tab=logs",
        )
        for ((input, expected) in fixtures) {
            assertEquals(UriValidationResult.Valid(expected), IntentUriValidator.validateInboundUri(input))
        }

        val manifestFile = resolveAppManifest()
        val manifest = manifestFile.readText()
        assertTrue(manifest.contains("android.intent.action.VIEW"))
        assertTrue(manifest.contains("android:scheme=\"https\""))
        assertTrue(manifest.contains("android:host=\"antigravity.google.com\""))
        assertTrue(manifest.contains("android:launchMode=\"singleTask\""))
        assertTrue(manifest.contains("android:windowSoftInputMode=\"adjustResize\""))
    }

    @Test
    fun c2_persistent_profile_directory_resolves_deterministically_under_app_files_dir() {
        val baseDir = File("/data/user/0/com.antigravity.remote/files")

        val first = AntigravityRemoteApp.resolveProfileDirectory(baseDir)
        val second = AntigravityRemoteApp.resolveProfileDirectory(baseDir)

        assertEquals(first, second)
        assertEquals(File(baseDir, "mozilla"), first)

        val manifest = resolveAppManifest().readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
    }

    @Test
    fun c3_supports_android_10_through_13_devices_and_display_cutout_insets() {
        val moduleDir = resolveModuleDir()
        val gradleConfig = File(moduleDir, "build.gradle.kts").readText()
        assertTrue("minSdk must support Android 10+ devices (API 29)", gradleConfig.contains("minSdk = 29"))
        assertTrue("targetSdk must remain 37", gradleConfig.contains("targetSdk = 37"))

        val mainActivitySource = File(moduleDir, "src/main/kotlin/com/antigravity/remote/ui/MainActivity.kt").readText()
        assertTrue("MainActivity must include displayCutout insets", mainActivitySource.contains("displayCutout()"))
        assertTrue("MainActivity must configure dark system bar styles", mainActivitySource.contains("SystemBarStyle.dark"))
        assertTrue("MainActivity must attach BasicSelectionActionDelegate", mainActivitySource.contains("BasicSelectionActionDelegate"))
        assertTrue("MainActivity must clear selection before release", mainActivitySource.contains("clearSelection()"))
        assertTrue("MainActivity must handle missing document picker", mainActivitySource.contains("ActivityNotFoundException"))
    }

    private fun resolveModuleDir(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "src/main").isDirectory) cwd else File(cwd, "app")
    }

    private fun resolveAppManifest(): File = File(resolveModuleDir(), "src/main/AndroidManifest.xml")
}

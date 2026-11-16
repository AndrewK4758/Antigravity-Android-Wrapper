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

    private fun resolveAppManifest(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val moduleDir = if (File(cwd, "src/main").isDirectory) cwd else File(cwd, "app")
        return File(moduleDir, "src/main/AndroidManifest.xml")
    }
}

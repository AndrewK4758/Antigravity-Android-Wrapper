package com.antigravity.remote

import com.antigravity.remote.policy.IntentUriValidator
import com.antigravity.remote.session.RemoteSessionCoordinator
import com.antigravity.remote.session.SessionErrorKind
import com.antigravity.remote.session.SessionUiState
import com.antigravity.remote.session.UiEffect
import com.antigravity.remote.telemetry.SeamTelemetry
import com.antigravity.remote.telemetry.SeamType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSessionErrorAndTelemetryTest {

    @Test
    fun s5_1_load_error_transitions_to_error_emits_diagnostic_and_recovers_on_retry() {
        val port = RecordingBrowserSessionPort()
        val sink = RecordingTelemetrySink()
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry(sink))
        coordinator.onStartup(
            runtimeError = null,
            initialIntentUri = "https://antigravity.google.com/r/inst-7",
            hasIntentData = true,
        )

        coordinator.onLoadError(
            uri = "https://antigravity.google.com/r/inst-7",
            categoryCode = 2,
            errorCode = -3,
        )

        val errorState = coordinator.uiState.value as SessionUiState.Error
        assertEquals(SessionErrorKind.NETWORK_OR_LOAD_FAILED, errorState.kind)
        assertEquals("https://antigravity.google.com/r/inst-7", errorState.retryUrl)
        assertTrue(
            sink.events.any {
                it.seamType == SeamType.BOUNDARY_FAULT && it.eventName == "gecko.runtime.init_or_load_fault"
            },
        )

        coordinator.onRetry()

        assertEquals(
            SessionUiState.Loading("https://antigravity.google.com/r/inst-7"),
            coordinator.uiState.value,
        )
        assertEquals(
            listOf(
                "https://antigravity.google.com/r/inst-7",
                "https://antigravity.google.com/r/inst-7",
            ),
            port.loadedUrls,
        )
    }

    @Test
    fun s5_2_runtime_init_failure_transitions_to_visible_error_without_loading() {
        val port = RecordingBrowserSessionPort()
        val sink = RecordingTelemetrySink()
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry(sink))

        coordinator.onStartup(
            runtimeError = UnsatisfiedLinkError("libxul.so missing"),
            initialIntentUri = null,
            hasIntentData = false,
        )

        val errorState = coordinator.uiState.value as SessionUiState.Error
        assertEquals(SessionErrorKind.RUNTIME_INIT_FAILED, errorState.kind)
        assertEquals(IntentUriValidator.DEFAULT_ENTRY_URL, errorState.retryUrl)
        assertEquals(emptyList<String>(), port.loadedUrls)
        assertTrue(
            sink.events.any {
                it.seamType == SeamType.BOUNDARY_FAULT && it.eventName == "gecko.runtime.init_or_load_fault"
            },
        )
    }

    @Test
    fun s2_3_cold_invalid_action_view_uri_enters_error_with_default_retry_url() {
        val port = RecordingBrowserSessionPort()
        val sink = RecordingTelemetrySink()
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry(sink))

        coordinator.onStartup(
            runtimeError = null,
            initialIntentUri = "https://evil.example.com/r/inst-1",
            hasIntentData = true,
        )

        val errorState = coordinator.uiState.value as SessionUiState.Error
        assertEquals(SessionErrorKind.INVALID_COLD_URI, errorState.kind)
        assertEquals(IntentUriValidator.DEFAULT_ENTRY_URL, errorState.retryUrl)
        assertEquals(emptyList<String>(), port.loadedUrls)
    }

    @Test
    fun s5_3_back_pressed_on_error_overlay_closes_application() = runTest {
        val port = RecordingBrowserSessionPort()
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry())
        coordinator.onCanGoBackChanged(true)
        coordinator.setStateForTest(
            SessionUiState.Error(
                kind = SessionErrorKind.NETWORK_OR_LOAD_FAILED,
                message = "Offline",
                retryUrl = IntentUriValidator.DEFAULT_ENTRY_URL,
            ),
        )
        val emittedEffects = mutableListOf<UiEffect>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.effects.collect { emittedEffects.add(it) }
        }

        coordinator.onBackPressed()

        assertEquals(listOf(UiEffect.CloseApp), emittedEffects)
        assertEquals(0, port.goBackCount)
        collectJob.cancel()
    }

    @Test
    fun s3_5_telemetry_and_url_persistence_never_capture_cookies_or_query_secrets() {
        val port = RecordingBrowserSessionPort()
        val sink = RecordingTelemetrySink()
        val telemetry = SeamTelemetry(sink)
        val savedUrls = mutableListOf<String>()
        val coordinator = RemoteSessionCoordinator(
            port = port,
            telemetry = telemetry,
            onValidatedUrlChanged = { savedUrls.add(it) },
        )

        coordinator.onStartup(runtimeError = null, initialIntentUri = null, hasIntentData = false)
        coordinator.onLoadStarted("https://accounts.google.com/ServiceLogin?token=secret123&code=oauth999")
        coordinator.onLoadError(
            uri = "https://accounts.google.com/ServiceLogin?token=secret123",
            categoryCode = 2,
            errorCode = -1,
        )
        telemetry.recordEvent(
            level = com.antigravity.remote.telemetry.TelemetryLevel.WARN,
            component = "NavigationDelegate",
            seamType = SeamType.BOUNDARY_FAULT,
            eventName = "gecko.runtime.init_or_load_fault",
            ruleAc = "AC-3",
            attributes = mapOf(
                "target" to "https://accounts.google.com/ServiceLogin?token=secret123",
                "Cookie" to "SID=xyz999; HSID=abc888",
                "Set-Cookie" to "LSID=topsecret456; HttpOnly",
                "Authorization" to "Bearer jwt-token-777",
            ),
        )

        val allJson = sink.jsonLines.joinToString("\n")
        for (forbidden in listOf("secret123", "oauth999", "SID=xyz999", "HSID=abc888", "LSID=topsecret456", "jwt-token-777")) {
            org.junit.Assert.assertFalse("Telemetry leaked forbidden secret: $forbidden", allJson.contains(forbidden))
        }
        org.junit.Assert.assertFalse(
            "Saved URL persistence must never store accounts.google.com auth URLs",
            savedUrls.any { it.contains("accounts.google.com") || it.contains("secret123") },
        )
    }
}

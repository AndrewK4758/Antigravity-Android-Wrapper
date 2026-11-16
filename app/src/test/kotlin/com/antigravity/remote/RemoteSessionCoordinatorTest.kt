package com.antigravity.remote

import com.antigravity.remote.session.BrowserSessionPort
import com.antigravity.remote.session.RemoteSessionCoordinator
import com.antigravity.remote.session.SessionUiState
import com.antigravity.remote.session.UiEffect
import com.antigravity.remote.telemetry.SeamTelemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSessionCoordinatorTest {

    @Test
    fun s2_4_empty_or_null_warm_intent_is_ignored_without_clobbering_active_session() {
        val port = FakeBrowserSessionPort()
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry())
        coordinator.setStateForTest(
            SessionUiState.Active(activeUrl = "https://antigravity.google.com/r/live-1", canGoBack = false),
        )

        coordinator.onWarmIntent(intentUri = null, hasIntentData = false)
        coordinator.onWarmIntent(intentUri = "   ", hasIntentData = true)

        assertEquals(
            SessionUiState.Active(activeUrl = "https://antigravity.google.com/r/live-1", canGoBack = false),
            coordinator.uiState.value,
        )
        assertEquals(emptyList<String>(), port.loadedUrls)
    }

    @Test
    fun s2_2_valid_warm_action_view_navigates_active_session_to_new_target() {
        val port = FakeBrowserSessionPort()
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry())
        coordinator.setStateForTest(
            SessionUiState.Active(activeUrl = "https://antigravity.google.com/", canGoBack = false),
        )

        coordinator.onWarmIntent(
            intentUri = "https://antigravity.google.com/r/next-inst?p=c%2Fxyz",
            hasIntentData = true,
        )

        assertEquals(
            listOf("https://antigravity.google.com/r/next-inst?p=c%2Fxyz"),
            port.loadedUrls,
        )
        assertEquals(
            SessionUiState.Loading(activeUrl = "https://antigravity.google.com/r/next-inst?p=c%2Fxyz"),
            coordinator.uiState.value,
        )
    }

    @Test
    fun s2_5_invalid_warm_action_view_never_clobbers_live_session_and_emits_notice() = runTest {
        val port = FakeBrowserSessionPort()
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry())
        coordinator.setStateForTest(
            SessionUiState.Active(activeUrl = "https://antigravity.google.com/r/live-1", canGoBack = true),
        )
        val emittedEffects = mutableListOf<UiEffect>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.effects.collect { emittedEffects.add(it) }
        }

        coordinator.onWarmIntent(
            intentUri = "https://evil.example.com/phish",
            hasIntentData = true,
        )

        assertEquals(
            SessionUiState.Active(activeUrl = "https://antigravity.google.com/r/live-1", canGoBack = true),
            coordinator.uiState.value,
        )
        assertEquals(emptyList<String>(), port.loadedUrls)
        assertTrue(emittedEffects.any { it is UiEffect.ShowNonFatalNotice })
        collectJob.cancel()
    }

    private class FakeBrowserSessionPort : BrowserSessionPort {
        val loadedUrls = mutableListOf<String>()
        var reloadCount = 0
        var goBackCount = 0
        var closePopupCount = 0
        val completedPrompts = mutableListOf<Pair<String, List<String>>>()
        val cancelledPrompts = mutableListOf<String>()

        override fun loadUri(url: String) {
            loadedUrls.add(url)
        }

        override fun reload() {
            reloadCount++
        }

        override fun goBack() {
            goBackCount++
        }

        override fun closePopup() {
            closePopupCount++
        }

        override fun completeFilePrompt(requestId: String, uris: List<String>) {
            completedPrompts.add(requestId to uris)
        }

        override fun cancelFilePrompt(requestId: String) {
            cancelledPrompts.add(requestId)
        }
    }
}

package com.antigravity.remote

import com.antigravity.remote.session.BrowserSessionPort
import com.antigravity.remote.session.RemoteSessionCoordinator
import com.antigravity.remote.session.SessionUiState
import com.antigravity.remote.telemetry.SeamTelemetry
import org.junit.Assert.assertEquals
import org.junit.Test

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

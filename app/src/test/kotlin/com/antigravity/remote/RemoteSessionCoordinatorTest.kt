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

    @Test
    fun s3_2_popup_request_marks_popup_open_without_overwriting_primary_url() {
        val port = FakeBrowserSessionPort()
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry())
        coordinator.setStateForTest(
            SessionUiState.Active(activeUrl = "https://antigravity.google.com/r/inst-1", canGoBack = false),
        )

        val decision = coordinator.onNavigationRequest(
            uri = "https://accounts.google.com/o/oauth2/v2/auth",
            isNewSession = true,
        )

        assertEquals(com.antigravity.remote.policy.NavigationDecision.ALLOW_POPUP_IN_HOST, decision)
        assertEquals(
            SessionUiState.Active(
                activeUrl = "https://antigravity.google.com/r/inst-1",
                canGoBack = false,
                popupOpen = true,
            ),
            coordinator.uiState.value,
        )
    }

    @Test
    fun s3_3_s3_4_coordinator_hands_off_external_https_and_blocks_unsafe_schemes() = runTest {
        val port = FakeBrowserSessionPort()
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry())
        coordinator.setStateForTest(
            SessionUiState.Active(activeUrl = "https://antigravity.google.com/r/inst-1", canGoBack = false),
        )
        val emittedEffects = mutableListOf<UiEffect>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.effects.collect { emittedEffects.add(it) }
        }

        val extDecision = coordinator.onNavigationRequest("https://docs.python.org/3/", isNewSession = false)
        val blockDecision = coordinator.onNavigationRequest("intent://scan/#Intent;end", isNewSession = false)

        assertEquals(com.antigravity.remote.policy.NavigationDecision.HAND_OFF_EXTERNAL, extDecision)
        assertEquals(com.antigravity.remote.policy.NavigationDecision.BLOCK, blockDecision)
        assertEquals(
            listOf(UiEffect.OpenExternalUrl("https://docs.python.org/3/")),
            emittedEffects,
        )
        assertEquals(
            SessionUiState.Active(activeUrl = "https://antigravity.google.com/r/inst-1", canGoBack = false),
            coordinator.uiState.value,
        )
        collectJob.cancel()
    }

    @Test
    fun s1_2_cold_launcher_start_loads_default_url_and_transitions_loading_to_active() {
        val port = FakeBrowserSessionPort()
        val persistedUrls = mutableListOf<String>()
        val coordinator = RemoteSessionCoordinator(
            port = port,
            telemetry = SeamTelemetry(),
            onValidatedUrlChanged = { persistedUrls.add(it) },
        )

        coordinator.onStartup(runtimeError = null, initialIntentUri = null, hasIntentData = false)

        assertEquals(listOf("https://antigravity.google.com/"), port.loadedUrls)
        assertEquals(SessionUiState.Loading("https://antigravity.google.com/"), coordinator.uiState.value)
        assertEquals(listOf("https://antigravity.google.com/"), persistedUrls)

        coordinator.onPageStop("https://antigravity.google.com/", success = true)

        assertEquals(
            SessionUiState.Active(activeUrl = "https://antigravity.google.com/", canGoBack = false),
            coordinator.uiState.value,
        )
    }

    @Test
    fun s1_3_saved_validated_url_restores_across_process_death_without_new_intent() {
        val port = FakeBrowserSessionPort()
        val saved = "https://antigravity.google.com/r/inst-42?p=c%2Fconv-9"
        val coordinator = RemoteSessionCoordinator(
            port = port,
            telemetry = SeamTelemetry(),
            initialSavedUrl = saved,
        )

        coordinator.onStartup(runtimeError = null, initialIntentUri = null, hasIntentData = false)

        assertEquals(listOf(saved), port.loadedUrls)
        assertEquals(SessionUiState.Loading(saved), coordinator.uiState.value)
    }

    @Test
    fun s2_1_cold_action_view_overrides_saved_url_with_validated_deep_link() {
        val port = FakeBrowserSessionPort()
        val coordinator = RemoteSessionCoordinator(
            port = port,
            telemetry = SeamTelemetry(),
            initialSavedUrl = "https://antigravity.google.com/r/old-inst",
        )

        coordinator.onStartup(
            runtimeError = null,
            initialIntentUri = "https://antigravity.google.com/r/dev-box?p=c%2Fabc123",
            hasIntentData = true,
        )

        assertEquals(
            listOf("https://antigravity.google.com/r/dev-box?p=c%2Fabc123"),
            port.loadedUrls,
        )
        assertEquals(
            SessionUiState.Loading("https://antigravity.google.com/r/dev-box?p=c%2Fabc123"),
            coordinator.uiState.value,
        )
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

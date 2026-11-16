package com.antigravity.remote

import com.antigravity.remote.policy.NavigationDecision
import com.antigravity.remote.session.BrowserSessionPort
import com.antigravity.remote.session.RemoteSessionCoordinator
import com.antigravity.remote.session.SessionErrorKind
import com.antigravity.remote.session.SessionUiState
import com.antigravity.remote.session.UiEffect
import com.antigravity.remote.telemetry.SeamTelemetry
import com.antigravity.remote.telemetry.SeamType
import com.antigravity.remote.ui.GeckoViewAttachmentHelper
import com.antigravity.remote.ui.SessionHostViewPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSessionJourneyTest {

    private enum class ChaosMode {
        DETERMINISTIC,
        SLOW_DOWNSTREAM,
        TOTAL_FAILURE,
        RESOURCE_EXHAUSTION,
        CONTEXT_DIVERGENCE,
    }

    private class StatefulGeckoEngineSimulator(
        private val mode: ChaosMode,
    ) : BrowserSessionPort {
        val callLog = mutableListOf<String>()
        val loadedUrls = mutableListOf<String>()
        var activeViewAttachmentCount = 0
        var coordinator: RemoteSessionCoordinator? = null
        var authRedirectBudget = 6
        var failLoadsRemaining = if (mode == ChaosMode.TOTAL_FAILURE) 2 else 0

        override fun loadUri(url: String) {
            callLog.add("loadUri:$url")
            loadedUrls.add(url)
            val coord = coordinator ?: return
            when (mode) {
                ChaosMode.DETERMINISTIC -> {
                    coord.onLoadStarted("https://accounts.google.com/ServiceLogin?continue=$url")
                    val popupDecision = coord.onNavigationRequest(
                        "https://accounts.google.com/o/oauth2/v2/auth",
                        isNewSession = true,
                    )
                    if (popupDecision == NavigationDecision.ALLOW_POPUP_IN_HOST) {
                        coord.onPopupClosed()
                    }
                    coord.onLoadStarted(url)
                    coord.onPageStop(url, success = true)
                }
                ChaosMode.SLOW_DOWNSTREAM -> {
                    coord.onLoadStarted(url)
                }
                ChaosMode.TOTAL_FAILURE -> {
                    coord.onLoadStarted(url)
                    if (failLoadsRemaining > 0) {
                        failLoadsRemaining--
                        coord.onLoadError(url, categoryCode = 2, errorCode = -3)
                    } else {
                        coord.onPageStop(url, success = true)
                    }
                }
                ChaosMode.RESOURCE_EXHAUSTION -> {
                    coord.onLoadStarted(url)
                    coord.onPageStop(url, success = true)
                }
                ChaosMode.CONTEXT_DIVERGENCE -> {
                    repeat(authRedirectBudget) { idx ->
                        coord.onLoadStarted("https://accounts.google.com/ServiceLogin?token=secret_cp11_$idx")
                    }
                }
            }
        }

        override fun reload() {
            callLog.add("reload")
        }

        override fun goBack() {
            callLog.add("goBack")
        }

        override fun closePopup() {
            callLog.add("closePopup")
        }

        override fun completeFilePrompt(requestId: String, uris: List<String>) {
            callLog.add("completeFilePrompt:$requestId")
        }

        override fun cancelFilePrompt(requestId: String) {
            callLog.add("cancelFilePrompt:$requestId")
        }
    }

    private class SimulatedHostView(
        private val engine: StatefulGeckoEngineSimulator,
    ) : SessionHostViewPort<String> {
        private var current: String? = null

        override fun currentAttachedSession(): String? = current

        override fun releaseAttachedSession() {
            if (current != null) {
                engine.activeViewAttachmentCount--
                current = null
            }
        }

        override fun attachSession(session: String) {
            current = session
            engine.activeViewAttachmentCount++
        }
    }

    @Test
    fun journey_pass1_deterministic_end_to_end_preserves_all_invariants() = runTest {
        val engine = StatefulGeckoEngineSimulator(ChaosMode.DETERMINISTIC)
        val sink = RecordingTelemetrySink()
        val savedUrls = mutableListOf<String>()
        val coordinator = RemoteSessionCoordinator(
            port = engine,
            telemetry = SeamTelemetry(sink),
            onValidatedUrlChanged = { savedUrls.add(it) },
        )
        engine.coordinator = coordinator
        val effects = mutableListOf<UiEffect>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.effects.collect { effects.add(it) }
        }

        val target = "https://antigravity.google.com/r/j-1?p=c%2F99"
        coordinator.onStartup(runtimeError = null, initialIntentUri = target, hasIntentData = true)
        coordinator.onFilePrompt("p-1", listOf("text/plain"), allowMultiple = false)
        coordinator.onFilePickerResult("p-1", listOf("content://docs/spec.txt"))

        assertEquals(SessionUiState.Active(target, canGoBack = false, popupOpen = false), coordinator.uiState.value)
        assertEquals(listOf(target), savedUrls)
        assertTrue(engine.callLog.contains("completeFilePrompt:p-1"))
        assertTrue(sink.events.none { it.seamType == SeamType.UNSOUND_CONDITION })
        assertTrue(sink.events.none { it.seamType == SeamType.DELTA_AUDIT_GAP })
        collectJob.cancel()
    }

    @Test
    fun journey_pass2_slow_downstream_keeps_target_cancellable_without_duplicate_loads() = runTest {
        val engine = StatefulGeckoEngineSimulator(ChaosMode.SLOW_DOWNSTREAM)
        val sink = RecordingTelemetrySink()
        val coordinator = RemoteSessionCoordinator(port = engine, telemetry = SeamTelemetry(sink))
        engine.coordinator = coordinator
        val effects = mutableListOf<UiEffect>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.effects.collect { effects.add(it) }
        }

        val target = "https://antigravity.google.com/r/j-1?p=c%2F99"
        coordinator.onStartup(runtimeError = null, initialIntentUri = target, hasIntentData = true)
        coordinator.onWarmIntent("https://evil.example.com/slow-phish", hasIntentData = true)

        assertEquals(SessionUiState.Loading(target, popupOpen = false), coordinator.uiState.value)
        assertEquals(listOf(target), engine.loadedUrls)
        assertTrue(effects.any { it is UiEffect.ShowNonFatalNotice })
        collectJob.cancel()
    }

    @Test
    fun journey_pass2_downstream_total_failure_recovers_cleanly_on_retry() {
        val engine = StatefulGeckoEngineSimulator(ChaosMode.TOTAL_FAILURE)
        val sink = RecordingTelemetrySink()
        val coordinator = RemoteSessionCoordinator(port = engine, telemetry = SeamTelemetry(sink))
        engine.coordinator = coordinator

        val target = "https://antigravity.google.com/r/j-7"
        coordinator.onStartup(runtimeError = null, initialIntentUri = target, hasIntentData = true)
        assertTrue(coordinator.uiState.value is SessionUiState.Error)

        coordinator.onRetry()
        assertTrue(coordinator.uiState.value is SessionUiState.Error)

        coordinator.onRetry()
        assertEquals(SessionUiState.Active(target, canGoBack = false), coordinator.uiState.value)
        assertEquals(listOf(target, target, target), engine.loadedUrls)
    }

    @Test
    fun journey_pass2_resource_exhaustion_prevents_double_attach_and_orphaned_prompts() {
        val engine = StatefulGeckoEngineSimulator(ChaosMode.RESOURCE_EXHAUSTION)
        val sink = RecordingTelemetrySink()
        val coordinator = RemoteSessionCoordinator(port = engine, telemetry = SeamTelemetry(sink))
        engine.coordinator = coordinator

        coordinator.onStartup(
            runtimeError = OutOfMemoryError("Native runtime pool exhausted"),
            initialIntentUri = null,
            hasIntentData = false,
        )
        assertEquals(
            SessionErrorKind.RUNTIME_INIT_FAILED,
            (coordinator.uiState.value as SessionUiState.Error).kind,
        )

        coordinator.onRetry()
        assertTrue(coordinator.uiState.value is SessionUiState.Active)

        coordinator.onFilePrompt("prompt-reattach", listOf("image/*"), allowMultiple = false)
        var currentHost = SimulatedHostView(engine)
        GeckoViewAttachmentHelper.rebindIfNeeded(currentHost, "session-1")
        repeat(20) {
            GeckoViewAttachmentHelper.detachOnStop(currentHost)
            val nextHost = SimulatedHostView(engine)
            val attached = GeckoViewAttachmentHelper.rebindIfNeeded(nextHost, "session-1")
            assertTrue(attached)
            assertEquals(1, engine.activeViewAttachmentCount)
            currentHost = nextHost
        }
        assertEquals("prompt-reattach", coordinator.pendingFilePromptRequestId())
        coordinator.onFilePickerResult("prompt-reattach", listOf("content://docs/ok.png"))
        assertEquals(null, coordinator.pendingFilePromptRequestId())
        assertTrue(sink.events.none { it.seamType == SeamType.DELTA_AUDIT_GAP })
    }

    @Test
    fun journey_pass2_context_divergence_halts_auth_loop_without_leaking_secrets() {
        val engine = StatefulGeckoEngineSimulator(ChaosMode.CONTEXT_DIVERGENCE)
        val sink = RecordingTelemetrySink()
        val savedUrls = mutableListOf<String>()
        val coordinator = RemoteSessionCoordinator(
            port = engine,
            telemetry = SeamTelemetry(sink),
            onValidatedUrlChanged = { savedUrls.add(it) },
        )
        engine.coordinator = coordinator

        val target = "https://antigravity.google.com/r/j-1?p=c%2F99"
        coordinator.onStartup(runtimeError = null, initialIntentUri = target, hasIntentData = true)
        coordinator.onWarmIntent("https://evil.example.com/steal?token=secret_fork", hasIntentData = true)

        assertTrue(coordinator.uiState.value is SessionUiState.Error)
        assertEquals(target, (coordinator.uiState.value as SessionUiState.Error).retryUrl)
        assertTrue(
            sink.events.any {
                it.seamType == SeamType.UNSOUND_CONDITION &&
                    it.eventName == "auth.session.context_diverged"
            },
        )
        val allTelemetry = sink.jsonLines.joinToString("\n")
        assertFalse(allTelemetry.contains("secret_cp11"))
        assertFalse(allTelemetry.contains("secret_fork"))
        assertTrue(savedUrls.all { it == target })
    }
}

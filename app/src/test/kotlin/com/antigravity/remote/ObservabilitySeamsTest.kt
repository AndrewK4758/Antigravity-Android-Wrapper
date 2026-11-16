package com.antigravity.remote

import com.antigravity.remote.session.RemoteSessionCoordinator
import com.antigravity.remote.session.RuntimeLifecycleGuard
import com.antigravity.remote.session.SessionErrorKind
import com.antigravity.remote.session.SessionUiState
import com.antigravity.remote.telemetry.SeamEvent
import com.antigravity.remote.telemetry.SeamTelemetry
import com.antigravity.remote.telemetry.SeamTelemetrySink
import com.antigravity.remote.telemetry.SeamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservabilitySeamsTest {

    @Test
    fun os1_1_unvalidated_load_triggers_unsound_condition_and_blocks_port_load() {
        val port = RecordingBrowserSessionPort()
        val sink = RecordingTelemetrySink()
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry(sink))

        val allowed = coordinator.loadUriGuarded("http://bad.test")

        assertFalse(allowed)
        assertEquals(emptyList<String>(), port.loadedUrls)
        assertTrue(
            sink.events.any {
                it.seamType == SeamType.UNSOUND_CONDITION &&
                    it.eventName == "intent.uri.unvalidated_load_blocked"
            },
        )
    }

    @Test
    fun os1_2_valid_antigravity_startup_emits_zero_unsound_condition_seams() {
        val port = RecordingBrowserSessionPort()
        val sink = RecordingTelemetrySink()
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry(sink))

        coordinator.onStartup(
            runtimeError = null,
            initialIntentUri = "https://antigravity.google.com/r/ok-1",
            hasIntentData = true,
        )

        assertTrue(sink.events.none { it.seamType == SeamType.UNSOUND_CONDITION })
        assertEquals(listOf("https://antigravity.google.com/r/ok-1"), port.loadedUrls)
    }

    @Test
    fun os1_3_navigation_policy_branches_increment_rule_divergence_counters() {
        val port = RecordingBrowserSessionPort()
        val sink = RecordingTelemetrySink()
        val telemetry = SeamTelemetry(sink)
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = telemetry)
        coordinator.setStateForTest(SessionUiState.Active("https://antigravity.google.com/", canGoBack = false))

        coordinator.onNavigationRequest("https://antigravity.google.com/", isNewSession = false)
        coordinator.onNavigationRequest("https://external.org/", isNewSession = false)

        assertEquals(1L, telemetry.ruleBranchCount("nav.policy.decision_distribution", "ALLOW_IN_SESSION"))
        assertEquals(1L, telemetry.ruleBranchCount("nav.policy.decision_distribution", "HAND_OFF_EXTERNAL"))
        assertTrue(
            sink.events.any {
                it.seamType == SeamType.RULE_DIVERGENCE && it.eventName == "nav.policy.decision_distribution"
            },
        )
    }

    @Test
    fun os1_4_warm_invalid_intent_emits_boundary_fault_without_clobbering_session() {
        val port = RecordingBrowserSessionPort()
        val sink = RecordingTelemetrySink()
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry(sink))
        coordinator.setStateForTest(SessionUiState.Active("https://antigravity.google.com/", canGoBack = false))

        coordinator.onWarmIntent("https://evil.invalid/", hasIntentData = true)

        assertEquals(SessionUiState.Active("https://antigravity.google.com/", canGoBack = false), coordinator.uiState.value)
        assertTrue(
            sink.events.any {
                it.seamType == SeamType.BOUNDARY_FAULT && it.eventName == "intent.uri.warm_clobber_prevented"
            },
        )
    }

    @Test
    fun os1_5_discarding_unresolved_file_prompt_triggers_delta_audit_gap() {
        val port = RecordingBrowserSessionPort()
        val sink = RecordingTelemetrySink()
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry(sink))

        coordinator.onFilePrompt("prompt-9", listOf("image/*"), allowMultiple = false)
        coordinator.onSessionReset()

        assertEquals(listOf("prompt-9"), port.cancelledPrompts)
        assertTrue(
            sink.events.any {
                it.seamType == SeamType.DELTA_AUDIT_GAP && it.eventName == "prompt.file.callback_orphaned"
            },
        )
    }

    @Test
    fun os1_6_normal_load_error_emits_zero_delta_audit_gaps_while_broken_sink_triggers_gap() {
        val port = RecordingBrowserSessionPort()
        val normalSink = RecordingTelemetrySink()
        val normalCoordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry(normalSink))

        normalCoordinator.onLoadError("https://antigravity.google.com/", categoryCode = 2, errorCode = -1)

        assertTrue(normalSink.events.none { it.seamType == SeamType.DELTA_AUDIT_GAP })
        assertTrue(normalSink.events.any { it.seamType == SeamType.BOUNDARY_FAULT })

        val gapEvents = mutableListOf<SeamEvent>()
        val flakySink = object : SeamTelemetrySink {
            override fun emit(jsonLine: String, event: SeamEvent) {
                if (event.seamType == SeamType.BOUNDARY_FAULT) {
                    throw IllegalStateException("Simulated telemetry sink drop")
                }
                gapEvents.add(event)
            }
        }
        val flakyCoordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry(flakySink))
        flakyCoordinator.onLoadError("https://antigravity.google.com/", categoryCode = 2, errorCode = -1)

        assertTrue(
            gapEvents.any {
                it.seamType == SeamType.DELTA_AUDIT_GAP && it.eventName == "error.ui.diagnostic_missing"
            },
        )
    }

    @Test
    fun os_runtime_guard_and_session_reattach_and_auth_loop_seams_fire_only_on_violation() {
        val sink = RecordingTelemetrySink()
        val telemetry = SeamTelemetry(sink)
        val guard = RuntimeLifecycleGuard(telemetry)

        var threwUninitialized = false
        try {
            guard.requireRuntimeInitializedForSession()
        } catch (_: IllegalStateException) {
            threwUninitialized = true
        }
        assertTrue(threwUninitialized)
        assertTrue(
            sink.events.any {
                it.seamType == SeamType.UNSOUND_CONDITION &&
                    it.eventName == "gecko.runtime.uninitialized_or_duplicate"
            },
        )

        guard.markRuntimeInitialized()
        guard.requireRuntimeInitializedForSession()

        var threwDuplicate = false
        try {
            guard.markRuntimeInitialized()
        } catch (_: IllegalStateException) {
            threwDuplicate = true
        }
        assertTrue(threwDuplicate)

        val port = RecordingBrowserSessionPort()
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = telemetry)
        coordinator.setStateForTest(SessionUiState.Active("https://antigravity.google.com/", canGoBack = false))
        coordinator.verifySessionAttachedOnStart(hasAttachedSession = true)
        assertTrue(sink.events.none { it.eventName == "lifecycle.config.session_reattach_missing" })

        coordinator.verifySessionAttachedOnStart(hasAttachedSession = false)
        assertTrue(
            sink.events.any {
                it.seamType == SeamType.DELTA_AUDIT_GAP &&
                    it.eventName == "lifecycle.config.session_reattach_missing"
            },
        )

        repeat(6) {
            coordinator.onLoadStarted("https://accounts.google.com/ServiceLogin?retry=$it")
        }
        assertTrue(coordinator.uiState.value is SessionUiState.Error)
        assertEquals(SessionErrorKind.NETWORK_OR_LOAD_FAILED, (coordinator.uiState.value as SessionUiState.Error).kind)
        assertTrue(
            sink.events.any {
                it.seamType == SeamType.UNSOUND_CONDITION &&
                    it.eventName == "auth.session.context_diverged"
            },
        )
    }
}

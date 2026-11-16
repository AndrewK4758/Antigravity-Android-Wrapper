package com.antigravity.remote

import com.antigravity.remote.session.RemoteSessionCoordinator
import com.antigravity.remote.session.SessionDisplayTracker
import com.antigravity.remote.session.SessionUiState
import com.antigravity.remote.session.UiEffect
import com.antigravity.remote.telemetry.SeamTelemetry
import com.antigravity.remote.telemetry.SeamType
import com.antigravity.remote.ui.ExternalActivityRouter
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
class LifecycleAndExternalHandoffTest {

    @Test
    fun s4_3_rotation_releases_old_view_before_reattaching_surviving_session_without_reload() {
        val events = mutableListOf<String>()
        val port = RecordingBrowserSessionPort()
        val sink = RecordingTelemetrySink()
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry(sink))
        coordinator.onStartup(
            runtimeError = null,
            initialIntentUri = "https://antigravity.google.com/r/inst-9",
            hasIntentData = true,
        )
        coordinator.onPageStop("https://antigravity.google.com/r/inst-9", success = true)
        assertEquals(1, port.loadedUrls.size)

        val sharedSession = "session-handle-1"
        val oldView = FakeSessionHostView("old", events, attached = sharedSession)
        val newView = FakeSessionHostView("new", events, attached = null)

        GeckoViewAttachmentHelper.detachOnStop(oldView)
        val attached = GeckoViewAttachmentHelper.rebindIfNeeded(newView, sharedSession)
        coordinator.verifySessionAttachedOnStart(hasAttachedSession = attached)

        assertTrue(attached)
        assertEquals(listOf("old:release", "new:attach:session-handle-1"), events)
        assertEquals(1, port.loadedUrls.size)
        assertTrue(sink.events.none { it.seamType == SeamType.DELTA_AUDIT_GAP })
    }

    @Test
    fun s4_6_missing_external_handler_emits_non_fatal_notice_without_terminating_session() = runTest {
        val port = RecordingBrowserSessionPort()
        val coordinator = RemoteSessionCoordinator(port = port, telemetry = SeamTelemetry())
        coordinator.setStateForTest(
            SessionUiState.Active("https://antigravity.google.com/r/inst-1", canGoBack = false),
        )
        val emittedEffects = mutableListOf<UiEffect>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.effects.collect { emittedEffects.add(it) }
        }

        val launched = ExternalActivityRouter.launchExternalUrl(
            url = "https://docs.python.org/3/",
            coordinator = coordinator,
            launcher = { throw IllegalStateException("No Activity found to handle Intent") },
        )

        assertFalse(launched)
        assertEquals(
            SessionUiState.Active("https://antigravity.google.com/r/inst-1", canGoBack = false),
            coordinator.uiState.value,
        )
        assertTrue(emittedEffects.any { it is UiEffect.ShowNonFatalNotice })
        collectJob.cancel()
    }

    @Test
    fun popup_session_waits_until_open_to_bind_and_restores_primary_on_close() {
        val tracker = SessionDisplayTracker<String>()
        tracker.onPrimaryCreated("primary-1")
        assertEquals("primary-1", tracker.currentDisplay())

        tracker.onPopupCreated("popup-1")
        assertEquals("primary-1", tracker.currentDisplay())
        assertFalse(tracker.activatePopupIfMatches("popup-1", isOpen = false))
        assertEquals("primary-1", tracker.currentDisplay())

        assertTrue(tracker.activatePopupIfMatches("popup-1", isOpen = true))
        assertEquals("popup-1", tracker.currentDisplay())
        assertFalse(tracker.activatePopupIfMatches("popup-1", isOpen = true))

        assertEquals("popup-1", tracker.clearPopup())
        assertEquals("primary-1", tracker.currentDisplay())
    }

    private class FakeSessionHostView(
        private val name: String,
        private val log: MutableList<String>,
        private var attached: String?,
    ) : SessionHostViewPort<String> {
        override fun currentAttachedSession(): String? = attached

        override fun releaseAttachedSession() {
            if (attached != null) {
                log.add("$name:release")
                attached = null
            }
        }

        override fun attachSession(session: String) {
            log.add("$name:attach:$session")
            attached = session
        }
    }
}

package com.antigravity.remote.session

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.antigravity.remote.AntigravityRemoteApp
import com.antigravity.remote.policy.NavigationDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.util.UUID

class SessionViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val app: AntigravityRemoteApp = getApplication()
    private val pendingPrompts = LinkedHashMap<String, PromptDelegate.FilePrompt>()
    private val pendingPromptResults = LinkedHashMap<String, GeckoResult<PromptDelegate.PromptResponse>>()

    private val displayTracker = SessionDisplayTracker<GeckoSession>()
    private val _displaySession = MutableStateFlow<GeckoSession?>(null)
    val displaySession: StateFlow<GeckoSession?> = _displaySession.asStateFlow()

    private var latestLocation: String? = null
    private var startupDispatched: Boolean = false

    val coordinator: RemoteSessionCoordinator = RemoteSessionCoordinator(
        port = GeckoBrowserPort(),
        telemetry = app.telemetry,
        initialSavedUrl = savedStateHandle[KEY_SAVED_URL],
        onValidatedUrlChanged = { validated -> savedStateHandle[KEY_SAVED_URL] = validated },
    )

    val uiState: StateFlow<SessionUiState> = coordinator.uiState
    val effects: SharedFlow<UiEffect> = coordinator.effects

    fun initializeIfNeeded(intent: Intent?) {
        if (startupDispatched && coordinator.uiState.value !is SessionUiState.Error) {
            return
        }
        val isActionView = intent?.action == Intent.ACTION_VIEW
        val intentUri = if (isActionView) intent?.dataString else null
        val hasIntentData = isActionView && intent?.data != null

        val runtimeResult = app.ensureGeckoRuntime()
        val runtime = runtimeResult.getOrNull()
        if (runtime == null) {
            coordinator.onStartup(runtimeResult.exceptionOrNull(), intentUri, hasIntentData)
            return
        }
        ensurePrimarySession(runtime)
        startupDispatched = true
        coordinator.onStartup(runtimeError = null, initialIntentUri = intentUri, hasIntentData = hasIntentData)
    }

    fun onWarmIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        coordinator.onWarmIntent(intent.dataString, intent.data != null)
    }

    fun currentDisplaySession(): GeckoSession? = displayTracker.currentDisplay()

    fun verifySessionAttachedOnStart(hasAttachedSession: Boolean) {
        coordinator.verifySessionAttachedOnStart(hasAttachedSession)
    }

    fun onBackPressed() = coordinator.onBackPressed()

    fun onRetry() {
        val runtimeResult = app.ensureGeckoRuntime()
        val runtime = runtimeResult.getOrNull()
        if (runtime == null) {
            coordinator.onStartup(runtimeResult.exceptionOrNull(), null, false)
            return
        }
        ensurePrimarySession(runtime)
        startupDispatched = true
        coordinator.onRetry()
    }

    fun onFilePickerResult(requestId: String, uris: List<Uri>?) {
        coordinator.onFilePickerResult(requestId, uris?.map { it.toString() })
    }

    private fun ensurePrimarySession(runtime: GeckoRuntime) {
        if (displayTracker.primarySession() != null) return
        app.requireRuntimeForSession()
        val session = createConfiguredSession()
        session.open(runtime)
        displayTracker.onPrimaryCreated(session)
        _displaySession.value = displayTracker.currentDisplay()
    }

    private fun createConfiguredSession(): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .allowJavascript(true)
            .useTrackingProtection(false)
            .displayMode(GeckoSessionSettings.DISPLAY_MODE_STANDALONE)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()
        return GeckoSession(settings).apply {
            navigationDelegate = createNavigationDelegate()
            progressDelegate = createProgressDelegate()
            promptDelegate = createPromptDelegate()
            contentDelegate = createContentDelegate()
        }
    }

    private fun createNavigationDelegate(): NavigationDelegate = object : NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: List<PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            promotePendingPopupIfOpen(session)
            if (session === displayTracker.primarySession() && !url.isNullOrBlank()) {
                latestLocation = url
            }
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            if (session === displayTracker.primarySession()) {
                coordinator.onCanGoBackChanged(canGoBack)
            }
        }

        override fun onLoadRequest(
            session: GeckoSession,
            request: NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny> {
            promotePendingPopupIfOpen(session)
            val decision = coordinator.onNavigationRequest(request.uri, isNewSession = false)
            val allow = decision == NavigationDecision.ALLOW_IN_SESSION
            return GeckoResult.fromValue(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            val decision = coordinator.onNavigationRequest(uri, isNewSession = true)
            if (decision != NavigationDecision.ALLOW_POPUP_IN_HOST) {
                return GeckoResult.fromValue(null)
            }
            closePopupSession()
            val child = createConfiguredSession()
            displayTracker.onPopupCreated(child)
            return GeckoResult.fromValue(child)
        }

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError,
        ): GeckoResult<String>? {
            if (session === displayTracker.primarySession()) {
                coordinator.onLoadError(uri ?: latestLocation, error.category, error.code)
            } else if (displayTracker.hasPopupSession(session)) {
                closePopupSession()
                coordinator.onPopupClosed()
                coordinator.emitNotice("Sign-in popup failed to load (error ${error.code})")
            }
            return GeckoResult.fromValue(null)
        }
    }

    private fun createProgressDelegate(): ProgressDelegate = object : ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            promotePendingPopupIfOpen(session)
            if (session === displayTracker.primarySession()) {
                latestLocation = url
                coordinator.onLoadStarted(url)
            }
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            promotePendingPopupIfOpen(session)
            if (session === displayTracker.primarySession()) {
                coordinator.onPageStop(latestLocation, success)
            }
        }
    }

    private fun createPromptDelegate(): PromptDelegate = object : PromptDelegate {
        override fun onFilePrompt(
            session: GeckoSession,
            prompt: PromptDelegate.FilePrompt,
        ): GeckoResult<PromptDelegate.PromptResponse> {
            val requestId = UUID.randomUUID().toString()
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            pendingPrompts[requestId] = prompt
            pendingPromptResults[requestId] = result
            val allowMultiple = prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE
            val mimeTypes = prompt.mimeTypes?.filter { it.isNotBlank() }.orEmpty()
            coordinator.onFilePrompt(requestId, mimeTypes, allowMultiple)
            return result
        }
    }

    private fun createContentDelegate(): ContentDelegate = object : ContentDelegate {
        override fun onCloseRequest(session: GeckoSession) {
            if (displayTracker.hasPopupSession(session)) {
                closePopupSession()
                coordinator.onPopupClosed()
            }
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            coordinator.onNavigationRequest(response.uri, isNewSession = false)
        }

        override fun onCrash(session: GeckoSession) = handleSessionTermination(session)

        override fun onKill(session: GeckoSession) = handleSessionTermination(session)
    }

    private fun promotePendingPopupIfOpen(session: GeckoSession) {
        if (session === displayTracker.pendingPopupSession() &&
            displayTracker.activatePopupIfMatches(session, isOpen = session.isOpen)
        ) {
            _displaySession.value = displayTracker.currentDisplay()
        }
    }

    private fun handleSessionTermination(session: GeckoSession) {
        if (displayTracker.hasPopupSession(session)) {
            closePopupSession()
            coordinator.onPopupClosed()
            return
        }
        closePopupSession()
        displayTracker.clearPrimary()?.close()
        _displaySession.value = displayTracker.currentDisplay()
        coordinator.onLoadError(latestLocation, -1, -1)
    }

    private fun closePopupSession() {
        displayTracker.clearPopup()?.close()
        _displaySession.value = displayTracker.currentDisplay()
    }

    override fun onCleared() {
        coordinator.onSessionReset()
        displayTracker.clearAll().forEach { it.close() }
        _displaySession.value = null
        super.onCleared()
    }

    private inner class GeckoBrowserPort : BrowserSessionPort {
        override fun loadUri(url: String) {
            val runtime = app.ensureGeckoRuntime().getOrNull() ?: return
            ensurePrimarySession(runtime)
            displayTracker.primarySession()?.loadUri(url)
        }

        override fun reload() {
            displayTracker.primarySession()?.reload()
        }

        override fun goBack() {
            displayTracker.primarySession()?.goBack()
        }

        override fun closePopup() {
            closePopupSession()
        }

        override fun completeFilePrompt(requestId: String, uris: List<String>) {
            val prompt = pendingPrompts.remove(requestId)
            val result = pendingPromptResults.remove(requestId) ?: return
            if (prompt == null || uris.isEmpty()) {
                if (prompt != null) result.complete(prompt.dismiss())
                return
            }
            val parsed = uris.map { Uri.parse(it) }
            val response = if (prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE) {
                prompt.confirm(app, parsed.toTypedArray())
            } else {
                prompt.confirm(app, parsed.first())
            }
            result.complete(response)
        }

        override fun cancelFilePrompt(requestId: String) {
            val prompt = pendingPrompts.remove(requestId)
            val result = pendingPromptResults.remove(requestId)
            if (prompt != null && result != null) {
                result.complete(prompt.dismiss())
            }
        }
    }

    private companion object {
        const val KEY_SAVED_URL = "validated_active_url"
    }
}

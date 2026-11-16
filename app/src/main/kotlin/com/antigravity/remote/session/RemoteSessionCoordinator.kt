package com.antigravity.remote.session

import com.antigravity.remote.policy.IntentUriValidator
import com.antigravity.remote.policy.NavigationDecision
import com.antigravity.remote.policy.NavigationOriginPolicy
import com.antigravity.remote.policy.UriValidationResult
import com.antigravity.remote.telemetry.SeamTelemetry
import com.antigravity.remote.telemetry.SeamType
import com.antigravity.remote.telemetry.TelemetryLevel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class RemoteSessionCoordinator(
    private val port: BrowserSessionPort,
    private val telemetry: SeamTelemetry,
    private val initialSavedUrl: String? = null,
    private val onValidatedUrlChanged: (String) -> Unit = {},
) {
    private val _uiState = MutableStateFlow<SessionUiState>(SessionUiState.Initializing)
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private val _effects = Channel<UiEffect>(Channel.BUFFERED)
    val effects: Flow<UiEffect> = _effects.receiveAsFlow()

    private var popupOpen: Boolean = false
    private var canGoBack: Boolean = false
    private var hasStarted: Boolean = false
    private var lastValidatedUrl: String = IntentUriValidator.DEFAULT_ENTRY_URL
    private var pendingFilePromptId: String? = null
    private var consecutiveAuthRedirects: Int = 0

    fun onStartup(runtimeError: Throwable?, initialIntentUri: String?, hasIntentData: Boolean) {
        if (hasStarted) return
        hasStarted = true
        consecutiveAuthRedirects = 0
        if (runtimeError != null) {
            reportRuntimeInitFailure(runtimeError)
            return
        }
        if (hasIntentData && !initialIntentUri.isNullOrBlank()) {
            when (val validation = IntentUriValidator.validateInboundUri(initialIntentUri)) {
                is UriValidationResult.Valid -> {
                    recordUriValidationOutcome("Valid")
                    loadUriGuarded(validation.normalizedUrl)
                }
                is UriValidationResult.Rejected -> {
                    recordUriValidationOutcome("Rejected")
                    transitionToError(
                        kind = SessionErrorKind.INVALID_COLD_URI,
                        message = "Blocked unsupported startup link (${validation.reason.name})",
                        retryUrl = IntentUriValidator.DEFAULT_ENTRY_URL,
                        violatedSeam = "intent.uri.cold_admission",
                        violationType = validation.reason.name,
                        "target" to validation.redactedTarget,
                    )
                }
            }
            return
        }
        val restored = initialSavedUrl?.let {
            (IntentUriValidator.validateInboundUri(it) as? UriValidationResult.Valid)?.normalizedUrl
        } ?: IntentUriValidator.DEFAULT_ENTRY_URL
        loadUriGuarded(restored)
    }

    fun onWarmIntent(intentUri: String?, hasIntentData: Boolean) {
        if (!hasIntentData || intentUri.isNullOrBlank()) return
        when (val validation = IntentUriValidator.validateInboundUri(intentUri)) {
            is UriValidationResult.Valid -> {
                recordUriValidationOutcome("Valid")
                consecutiveAuthRedirects = 0
                loadUriGuarded(validation.normalizedUrl)
            }
            is UriValidationResult.Rejected -> {
                recordUriValidationOutcome("Rejected")
                emitSeam(
                    TelemetryLevel.WARN,
                    SeamType.BOUNDARY_FAULT,
                    "intent.uri.warm_clobber_prevented",
                    "AC-4",
                    mapOf("reason" to validation.reason.name, "target" to validation.redactedTarget),
                )
                sendEffect(UiEffect.ShowNonFatalNotice("Ignored unsupported link (${validation.reason.name})"))
            }
        }
    }

    fun onNavigationRequest(uri: String?, isNewSession: Boolean): NavigationDecision {
        val decision = NavigationOriginPolicy.classify(uri, isNewSession)
        emitSeam(
            TelemetryLevel.INFO, SeamType.RULE_DIVERGENCE, "nav.policy.decision_distribution", "AC-3,AC-6",
            mapOf("branch_taken" to decision.name),
        )
        when (decision) {
            NavigationDecision.ALLOW_POPUP_IN_HOST -> {
                popupOpen = true
                _uiState.value = when (val current = _uiState.value) {
                    is SessionUiState.Loading -> current.copy(popupOpen = true)
                    is SessionUiState.Active -> current.copy(popupOpen = true)
                    else -> current
                }
            }
            NavigationDecision.HAND_OFF_EXTERNAL ->
                uri?.trim()?.takeIf { it.isNotEmpty() }?.let { sendEffect(UiEffect.OpenExternalUrl(it)) }
            NavigationDecision.ALLOW_IN_SESSION, NavigationDecision.BLOCK -> Unit
        }
        return decision
    }

    fun onDownloadRequested(uri: String?) {
        if (NavigationOriginPolicy.classify(uri, isNewSession = false) == NavigationDecision.HAND_OFF_EXTERNAL) {
            uri?.trim()?.takeIf { it.isNotEmpty() }?.let { sendEffect(UiEffect.OpenExternalUrl(it)) }
            return
        }
        emitSeam(
            TelemetryLevel.WARN, SeamType.BOUNDARY_FAULT, "download.response.unhandled", "AC-6",
            mapOf("target" to IntentUriValidator.redactForLog(uri)),
        )
        sendEffect(UiEffect.ShowNonFatalNotice("Direct file download is not supported in this standalone session"))
    }

    fun onLoadStarted(uri: String?) {
        val current = _uiState.value
        if (current is SessionUiState.Error) return
        val candidate = uri?.trim().takeUnless { it.isNullOrEmpty() }
        if (candidate != null && NavigationOriginPolicy.isAccountsHost(candidate)) {
            consecutiveAuthRedirects++
            if (consecutiveAuthRedirects >= MAX_AUTH_REDIRECTS) {
                telemetry.recordUnsoundCondition(
                    component = "RemoteSessionCoordinator",
                    eventName = "auth.session.context_diverged",
                    ruleAc = "AC-3",
                    precondition = "Auth redirect sequence must converge",
                    expectedState = "settled_antigravity_session",
                    actualState = "redirect_loop_$consecutiveAuthRedirects",
                    codeLocation = "RemoteSessionCoordinator:onLoadStarted",
                )
                closePopupIfOpen()
                _uiState.value = SessionUiState.Error(
                    kind = SessionErrorKind.NETWORK_OR_LOAD_FAILED,
                    message = "Sign-in redirect loop detected. Tap Retry to reload.",
                    retryUrl = lastValidatedUrl,
                )
                return
            }
        }
        val activeUrl = resolveAllowlistedActiveUrl(candidate, current, persistValidated = true)
        _uiState.value = SessionUiState.Loading(activeUrl = activeUrl, popupOpen = popupOpen)
    }

    fun onPageStop(uri: String?, success: Boolean) {
        val current = _uiState.value
        if (current is SessionUiState.Error || !success) return
        val candidate = uri?.trim().takeUnless { it.isNullOrEmpty() }
        if (candidate != null &&
            !NavigationOriginPolicy.isAccountsHost(candidate) &&
            IntentUriValidator.validateInboundUri(candidate) is UriValidationResult.Valid
        ) {
            consecutiveAuthRedirects = 0
        }
        val activeUrl = resolveAllowlistedActiveUrl(candidate, current, persistValidated = false)
        _uiState.value = SessionUiState.Active(activeUrl = activeUrl, canGoBack = canGoBack, popupOpen = popupOpen)
    }

    fun onLoadError(uri: String?, categoryCode: Int, errorCode: Int) {
        val current = _uiState.value
        val uriValid = uri?.let { IntentUriValidator.validateInboundUri(it) as? UriValidationResult.Valid }
        val loadingValid = (current as? SessionUiState.Loading)?.activeUrl?.let {
            IntentUriValidator.validateInboundUri(it) as? UriValidationResult.Valid
        }
        val candidateRetry = uriValid?.normalizedUrl ?: loadingValid?.normalizedUrl ?: lastValidatedUrl
        transitionToError(
            kind = SessionErrorKind.NETWORK_OR_LOAD_FAILED,
            message = "Unable to load session page (category=$categoryCode, code=$errorCode)",
            retryUrl = candidateRetry,
            violatedSeam = "gecko.session.load",
            violationType = "load_error",
            "category_code" to categoryCode.toString(),
            "error_code" to errorCode.toString(),
            "target" to IntentUriValidator.redactForLog(uri ?: candidateRetry),
        )
    }

    fun onRetry(runtimeError: Throwable? = null) {
        hasStarted = true
        consecutiveAuthRedirects = 0
        if (runtimeError != null) {
            reportRuntimeInitFailure(runtimeError)
            return
        }
        val target = when (val current = _uiState.value) {
            is SessionUiState.Error ->
                (IntentUriValidator.validateInboundUri(current.retryUrl) as? UriValidationResult.Valid)?.normalizedUrl
                    ?: IntentUriValidator.DEFAULT_ENTRY_URL
            is SessionUiState.Loading -> current.activeUrl
            is SessionUiState.Active -> current.activeUrl
            SessionUiState.Initializing -> lastValidatedUrl
        }
        loadUriGuarded(target)
    }

    fun onCanGoBackChanged(canGoBack: Boolean) {
        this.canGoBack = canGoBack
        val current = _uiState.value
        if (current is SessionUiState.Active) {
            _uiState.value = current.copy(canGoBack = canGoBack)
        }
    }

    fun onBackPressed() {
        if (_uiState.value is SessionUiState.Error) {
            sendEffect(UiEffect.CloseApp)
            return
        }
        if (popupOpen) {
            port.closePopup()
            onPopupClosed()
            return
        }
        if (canGoBack) {
            port.goBack()
            return
        }
        sendEffect(UiEffect.CloseApp)
    }

    fun onPopupClosed() {
        popupOpen = false
        _uiState.value = when (val current = _uiState.value) {
            is SessionUiState.Loading -> current.copy(popupOpen = false)
            is SessionUiState.Active -> current.copy(popupOpen = false)
            else -> current
        }
    }

    fun onFilePrompt(requestId: String, mimeTypes: List<String>, allowMultiple: Boolean) {
        onSessionReset()
        pendingFilePromptId = requestId
        sendEffect(UiEffect.LaunchDocumentPicker(requestId, mimeTypes, allowMultiple))
    }

    fun onFilePickerResult(requestId: String, uris: List<String>?) {
        if (pendingFilePromptId != requestId) return
        pendingFilePromptId = null
        if (uris.isNullOrEmpty()) port.cancelFilePrompt(requestId) else port.completeFilePrompt(requestId, uris)
    }

    fun onSessionReset() {
        val existing = pendingFilePromptId ?: return
        pendingFilePromptId = null
        port.cancelFilePrompt(existing)
        telemetry.recordDeltaAuditGap(
            "RemoteSessionCoordinator", "prompt.file.callback_orphaned", "AC-6", "checkpoint_skipped",
            mapOf("request_id" to existing),
        )
    }

    fun loadUriGuarded(rawUri: String): Boolean {
        return when (val validation = IntentUriValidator.validateInboundUri(rawUri)) {
            is UriValidationResult.Valid -> {
                val normalized = validation.normalizedUrl
                lastValidatedUrl = normalized
                onValidatedUrlChanged(normalized)
                _uiState.value = SessionUiState.Loading(activeUrl = normalized, popupOpen = popupOpen)
                try {
                    port.loadUri(normalized)
                    true
                } catch (t: Throwable) {
                    reportRuntimeInitFailure(t)
                    false
                }
            }
            is UriValidationResult.Rejected -> {
                telemetry.recordUnsoundCondition(
                    component = "RemoteSessionCoordinator",
                    eventName = "intent.uri.unvalidated_load_blocked",
                    ruleAc = "AC-4",
                    precondition = "Only validated antigravity HTTPS URIs may enter primary loadUri",
                    expectedState = "UriValidationResult.Valid",
                    actualState = validation.reason.name,
                    codeLocation = "RemoteSessionCoordinator:loadUriGuarded",
                    extra = mapOf("target" to validation.redactedTarget),
                )
                false
            }
        }
    }

    fun verifySessionAttachedOnStart(hasAttachedSession: Boolean) {
        if (_uiState.value is SessionUiState.Active && !hasAttachedSession) {
            telemetry.recordDeltaAuditGap(
                "RemoteSessionCoordinator", "lifecycle.config.session_reattach_missing", "AC-5", "side_effect_missing",
                mapOf("expected_checkpoint" to "GeckoView.setSession"),
            )
        }
    }

    fun emitNotice(message: String) { sendEffect(UiEffect.ShowNonFatalNotice(message)) }

    fun pendingFilePromptRequestId(): String? = pendingFilePromptId

    private fun resolveAllowlistedActiveUrl(candidate: String?, current: SessionUiState, persistValidated: Boolean): String {
        if (candidate != null && NavigationOriginPolicy.classify(candidate, isNewSession = false) == NavigationDecision.ALLOW_IN_SESSION) {
            if (persistValidated) {
                (IntentUriValidator.validateInboundUri(candidate) as? UriValidationResult.Valid)?.let {
                    if (lastValidatedUrl != it.normalizedUrl) {
                        lastValidatedUrl = it.normalizedUrl
                        onValidatedUrlChanged(it.normalizedUrl)
                    }
                }
            }
            return candidate
        }
        return when (current) {
            is SessionUiState.Loading -> current.activeUrl
            is SessionUiState.Active -> current.activeUrl
            else -> lastValidatedUrl
        }
    }

    private fun reportRuntimeInitFailure(runtimeError: Throwable) {
        transitionToError(
            kind = SessionErrorKind.RUNTIME_INIT_FAILED,
            message = "Browser engine failed to initialize: ${runtimeError::class.simpleName}",
            retryUrl = IntentUriValidator.DEFAULT_ENTRY_URL,
            violatedSeam = "gecko.runtime.init",
            violationType = "runtime_init_exception",
            "error_type" to (runtimeError::class.simpleName ?: "Throwable"),
        )
    }

    private fun closePopupIfOpen() {
        if (popupOpen) {
            port.closePopup()
            popupOpen = false
        }
    }

    private fun transitionToError(
        kind: SessionErrorKind,
        message: String,
        retryUrl: String,
        violatedSeam: String,
        violationType: String,
        vararg extraAttributes: Pair<String, String>,
    ) {
        closePopupIfOpen()
        val attributes = linkedMapOf("violated_seam" to violatedSeam, "violation_type" to violationType)
        extraAttributes.forEach { (k, v) -> attributes[k] = v }
        val beforeCount = telemetry.emittedCount()
        emitSeam(TelemetryLevel.ERROR, SeamType.BOUNDARY_FAULT, "gecko.runtime.init_or_load_fault", "AC-7", attributes)
        if (telemetry.emittedCount() == beforeCount) {
            telemetry.recordDeltaAuditGap(
                "RemoteSessionCoordinator", "error.ui.diagnostic_missing", "AC-7", "side_effect_missing",
            )
        }
        _uiState.value = SessionUiState.Error(kind = kind, message = message, retryUrl = retryUrl)
    }

    private fun sendEffect(effect: UiEffect) { _effects.trySend(effect) }

    private fun recordUriValidationOutcome(branch: String) {
        emitSeam(
            if (branch == "Valid") TelemetryLevel.INFO else TelemetryLevel.WARN,
            SeamType.RULE_DIVERGENCE, "intent.uri.validation_outcome", "AC-4", mapOf("branch_taken" to branch),
        )
    }

    private fun emitSeam(
        level: TelemetryLevel, seamType: SeamType, eventName: String, ruleAc: String, attributes: Map<String, String>,
    ) = telemetry.recordEvent(level, "RemoteSessionCoordinator", seamType, eventName, ruleAc, attributes)

    internal fun setStateForTest(state: SessionUiState) {
        _uiState.value = state
        when (state) {
            is SessionUiState.Active -> { canGoBack = state.canGoBack; popupOpen = state.popupOpen; hasStarted = true }
            is SessionUiState.Loading -> { popupOpen = state.popupOpen; hasStarted = true }
            else -> Unit
        }
    }

    private companion object { const val MAX_AUTH_REDIRECTS = 6 }
}

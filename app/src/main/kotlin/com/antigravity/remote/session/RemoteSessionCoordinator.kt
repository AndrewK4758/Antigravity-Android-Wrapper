package com.antigravity.remote.session

import com.antigravity.remote.policy.IntentUriValidator
import com.antigravity.remote.policy.NavigationDecision
import com.antigravity.remote.policy.NavigationOriginPolicy
import com.antigravity.remote.policy.UriValidationResult
import com.antigravity.remote.telemetry.SeamTelemetry
import com.antigravity.remote.telemetry.SeamType
import com.antigravity.remote.telemetry.TelemetryLevel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class RemoteSessionCoordinator(
    private val port: BrowserSessionPort,
    private val telemetry: SeamTelemetry,
    private val initialSavedUrl: String? = null,
    private val onValidatedUrlChanged: (String) -> Unit = {},
) {
    private val _uiState = MutableStateFlow<SessionUiState>(SessionUiState.Initializing)
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<UiEffect>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<UiEffect> = _effects.asSharedFlow()

    private var popupOpen: Boolean = false
    private var canGoBack: Boolean = false
    private var hasStarted: Boolean = false
    private var lastValidatedUrl: String = IntentUriValidator.DEFAULT_ENTRY_URL
    private var pendingFilePromptId: String? = null
    private var consecutiveAuthRedirects: Int = 0

    fun onStartup(runtimeError: Throwable?, initialIntentUri: String?, hasIntentData: Boolean) {
        if (runtimeError != null) {
            transitionToError(
                kind = SessionErrorKind.RUNTIME_INIT_FAILED,
                message = "Browser engine failed to initialize: ${runtimeError::class.simpleName}",
                retryUrl = IntentUriValidator.DEFAULT_ENTRY_URL,
                eventName = "gecko.runtime.init_or_load_fault",
                attributes = mapOf(
                    "violated_seam" to "gecko.runtime.init",
                    "violation_type" to "runtime_init_exception",
                    "error_type" to (runtimeError::class.simpleName ?: "Throwable"),
                ),
            )
            return
        }
        if (hasStarted && _uiState.value !is SessionUiState.Error) return
        consecutiveAuthRedirects = 0
        if (hasIntentData && !initialIntentUri.isNullOrBlank()) {
            when (val validation = IntentUriValidator.validateInboundUri(initialIntentUri)) {
                is UriValidationResult.Valid -> {
                    recordUriValidationOutcome("Valid")
                    loadValidatedUrl(validation.normalizedUrl)
                    hasStarted = true
                }
                is UriValidationResult.Rejected -> {
                    recordUriValidationOutcome("Rejected")
                    transitionToError(
                        kind = SessionErrorKind.INVALID_COLD_URI,
                        message = "Blocked unsupported startup link (${validation.reason.name})",
                        retryUrl = IntentUriValidator.DEFAULT_ENTRY_URL,
                        eventName = "gecko.runtime.init_or_load_fault",
                        attributes = mapOf(
                            "violated_seam" to "intent.uri.cold_admission",
                            "violation_type" to validation.reason.name,
                            "target" to validation.redactedTarget,
                        ),
                    )
                }
            }
            return
        }
        val restored = initialSavedUrl?.let {
            (IntentUriValidator.validateInboundUri(it) as? UriValidationResult.Valid)?.normalizedUrl
        } ?: IntentUriValidator.DEFAULT_ENTRY_URL
        loadValidatedUrl(restored)
        hasStarted = true
    }

    fun onWarmIntent(intentUri: String?, hasIntentData: Boolean) {
        if (!hasIntentData || intentUri.isNullOrBlank()) return
        when (val validation = IntentUriValidator.validateInboundUri(intentUri)) {
            is UriValidationResult.Valid -> {
                recordUriValidationOutcome("Valid")
                loadValidatedUrl(validation.normalizedUrl)
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
                _effects.tryEmit(UiEffect.ShowNonFatalNotice("Ignored unsupported link (${validation.reason.name})"))
            }
        }
    }

    fun onNavigationRequest(uri: String?, isNewSession: Boolean): NavigationDecision {
        val decision = NavigationOriginPolicy.classify(uri, isNewSession)
        emitSeam(
            TelemetryLevel.INFO,
            SeamType.RULE_DIVERGENCE,
            "nav.policy.decision_distribution",
            "AC-3,AC-6",
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
            NavigationDecision.HAND_OFF_EXTERNAL -> {
                uri?.trim()?.takeIf { it.isNotEmpty() }?.let { _effects.tryEmit(UiEffect.OpenExternalUrl(it)) }
            }
            NavigationDecision.ALLOW_IN_SESSION, NavigationDecision.BLOCK -> Unit
        }
        return decision
    }

    fun onLoadStarted(uri: String?) {
        val current = _uiState.value
        if (current is SessionUiState.Error) return
        val candidate = uri?.trim().takeUnless { it.isNullOrEmpty() }
        if (candidate != null && isAccountsHost(candidate)) {
            consecutiveAuthRedirects++
            if (consecutiveAuthRedirects >= MAX_AUTH_REDIRECTS) {
                emitSeam(
                    TelemetryLevel.ERROR,
                    SeamType.UNSOUND_CONDITION,
                    "auth.session.context_diverged",
                    "AC-3",
                    mapOf(
                        "precondition" to "Auth redirect sequence must converge",
                        "expected_state" to "settled_antigravity_session",
                        "actual_state" to "redirect_loop_$consecutiveAuthRedirects",
                        "code_location" to "RemoteSessionCoordinator:onLoadStarted",
                    ),
                )
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
        if (candidate != null && !isAccountsHost(candidate) && IntentUriValidator.validateInboundUri(candidate) is UriValidationResult.Valid) {
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
            eventName = "gecko.runtime.init_or_load_fault",
            attributes = mapOf(
                "violated_seam" to "gecko.session.load",
                "violation_type" to "load_error",
                "category_code" to categoryCode.toString(),
                "error_code" to errorCode.toString(),
                "target" to IntentUriValidator.redactForLog(uri ?: candidateRetry),
            ),
        )
    }

    fun onRetry() {
        consecutiveAuthRedirects = 0
        val target = when (val current = _uiState.value) {
            is SessionUiState.Error ->
                (IntentUriValidator.validateInboundUri(current.retryUrl) as? UriValidationResult.Valid)?.normalizedUrl
                    ?: IntentUriValidator.DEFAULT_ENTRY_URL
            is SessionUiState.Loading -> current.activeUrl
            is SessionUiState.Active -> current.activeUrl
            SessionUiState.Initializing -> lastValidatedUrl
        }
        loadValidatedUrl(target)
        hasStarted = true
    }

    fun onCanGoBackChanged(canGoBack: Boolean) {
        this.canGoBack = canGoBack
        val current = _uiState.value
        if (current is SessionUiState.Active) {
            _uiState.value = current.copy(canGoBack = canGoBack)
        }
    }

    fun onBackPressed() {
        if (popupOpen) {
            port.closePopup()
            onPopupClosed()
            return
        }
        if (_uiState.value !is SessionUiState.Error && canGoBack) {
            port.goBack()
            return
        }
        _effects.tryEmit(UiEffect.CloseApp)
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
        _effects.tryEmit(UiEffect.LaunchDocumentPicker(requestId, mimeTypes, allowMultiple))
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
        emitSeam(
            TelemetryLevel.ERROR,
            SeamType.DELTA_AUDIT_GAP,
            "prompt.file.callback_orphaned",
            "AC-6",
            mapOf("gap_type" to "checkpoint_skipped", "request_id" to existing),
        )
    }

    fun loadUriGuarded(rawUri: String): Boolean {
        return when (val validation = IntentUriValidator.validateInboundUri(rawUri)) {
            is UriValidationResult.Valid -> {
                loadValidatedUrl(validation.normalizedUrl)
                true
            }
            is UriValidationResult.Rejected -> {
                emitSeam(
                    TelemetryLevel.ERROR,
                    SeamType.UNSOUND_CONDITION,
                    "intent.uri.unvalidated_load_blocked",
                    "AC-4",
                    mapOf(
                        "precondition" to "Only validated antigravity HTTPS URIs may enter primary loadUri",
                        "expected_state" to "UriValidationResult.Valid",
                        "actual_state" to validation.reason.name,
                        "code_location" to "RemoteSessionCoordinator:loadUriGuarded",
                        "target" to validation.redactedTarget,
                    ),
                )
                false
            }
        }
    }

    fun verifySessionAttachedOnStart(hasAttachedSession: Boolean) {
        if (_uiState.value is SessionUiState.Active && !hasAttachedSession) {
            emitSeam(
                TelemetryLevel.ERROR, SeamType.DELTA_AUDIT_GAP,
                "lifecycle.config.session_reattach_missing", "AC-5",
                mapOf("expected_checkpoint" to "GeckoView.setSession", "gap_type" to "side_effect_missing"),
            )
        }
    }

    fun emitNotice(message: String) { _effects.tryEmit(UiEffect.ShowNonFatalNotice(message)) }

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

    private fun transitionToError(
        kind: SessionErrorKind, message: String, retryUrl: String, eventName: String, attributes: Map<String, String>,
    ) {
        val beforeCount = telemetry.emittedCount()
        emitSeam(TelemetryLevel.ERROR, SeamType.BOUNDARY_FAULT, eventName, "AC-7", attributes)
        if (telemetry.emittedCount() == beforeCount) {
            emitSeam(
                TelemetryLevel.ERROR, SeamType.DELTA_AUDIT_GAP,
                "error.ui.diagnostic_missing", "AC-7", mapOf("gap_type" to "side_effect_missing"),
            )
        }
        _uiState.value = SessionUiState.Error(kind = kind, message = message, retryUrl = retryUrl)
    }

    private fun loadValidatedUrl(validatedUrl: String) {
        lastValidatedUrl = validatedUrl
        onValidatedUrlChanged(validatedUrl)
        _uiState.value = SessionUiState.Loading(activeUrl = validatedUrl, popupOpen = popupOpen)
        port.loadUri(validatedUrl)
    }

    private fun recordUriValidationOutcome(branch: String) {
        emitSeam(
            if (branch == "Valid") TelemetryLevel.INFO else TelemetryLevel.WARN,
            SeamType.RULE_DIVERGENCE, "intent.uri.validation_outcome", "AC-4", mapOf("branch_taken" to branch),
        )
    }

    private fun emitSeam(
        level: TelemetryLevel, seamType: SeamType, eventName: String, ruleAc: String, attributes: Map<String, String>,
    ) = telemetry.recordEvent(level, "RemoteSessionCoordinator", seamType, eventName, ruleAc, attributes)

    private fun isAccountsHost(url: String): Boolean = url.trim().lowercase().startsWith("https://accounts.google.com")

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

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
        if (hasStarted && _uiState.value !is SessionUiState.Error) {
            return
        }
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
        val restored = initialSavedUrl?.let { saved ->
            (IntentUriValidator.validateInboundUri(saved) as? UriValidationResult.Valid)?.normalizedUrl
        } ?: IntentUriValidator.DEFAULT_ENTRY_URL
        loadValidatedUrl(restored)
        hasStarted = true
    }

    fun onWarmIntent(intentUri: String?, hasIntentData: Boolean) {
        if (!hasIntentData || intentUri.isNullOrBlank()) {
            return
        }
        when (val validation = IntentUriValidator.validateInboundUri(intentUri)) {
            is UriValidationResult.Valid -> {
                recordUriValidationOutcome("Valid")
                loadValidatedUrl(validation.normalizedUrl)
            }
            is UriValidationResult.Rejected -> {
                recordUriValidationOutcome("Rejected")
                telemetry.recordEvent(
                    level = TelemetryLevel.WARN,
                    component = "RemoteSessionCoordinator",
                    seamType = SeamType.BOUNDARY_FAULT,
                    eventName = "intent.uri.warm_clobber_prevented",
                    ruleAc = "AC-4",
                    attributes = mapOf(
                        "reason" to validation.reason.name,
                        "target" to validation.redactedTarget,
                    ),
                )
                _effects.tryEmit(
                    UiEffect.ShowNonFatalNotice("Ignored unsupported link (${validation.reason.name})"),
                )
            }
        }
    }

    fun onNavigationRequest(uri: String?, isNewSession: Boolean): NavigationDecision {
        val decision = NavigationOriginPolicy.classify(uri, isNewSession)
        telemetry.recordEvent(
            level = TelemetryLevel.INFO,
            component = "RemoteSessionCoordinator",
            seamType = SeamType.RULE_DIVERGENCE,
            eventName = "nav.policy.decision_distribution",
            ruleAc = "AC-3,AC-6",
            attributes = mapOf("branch_taken" to decision.name),
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
                val safeUrl = uri?.trim().orEmpty()
                if (safeUrl.isNotEmpty()) {
                    _effects.tryEmit(UiEffect.OpenExternalUrl(safeUrl))
                }
            }
            NavigationDecision.ALLOW_IN_SESSION, NavigationDecision.BLOCK -> Unit
        }
        return decision
    }

    fun onLoadStarted(uri: String?) {
        val current = _uiState.value
        if (current is SessionUiState.Error) return
        val candidate = uri?.trim().takeUnless { it.isNullOrEmpty() }
        val activeUrl = when {
            candidate != null && NavigationOriginPolicy.classify(candidate, isNewSession = false) == NavigationDecision.ALLOW_IN_SESSION -> {
                val validated = IntentUriValidator.validateInboundUri(candidate)
                if (validated is UriValidationResult.Valid) {
                    lastValidatedUrl = validated.normalizedUrl
                    onValidatedUrlChanged(validated.normalizedUrl)
                }
                candidate
            }
            current is SessionUiState.Loading -> current.activeUrl
            current is SessionUiState.Active -> current.activeUrl
            else -> lastValidatedUrl
        }
        _uiState.value = SessionUiState.Loading(activeUrl = activeUrl, popupOpen = popupOpen)
    }

    fun onPageStop(uri: String?, success: Boolean) {
        val current = _uiState.value
        if (current is SessionUiState.Error) return
        if (!success) return
        val candidate = uri?.trim().takeUnless { it.isNullOrEmpty() }
        val activeUrl = when {
            candidate != null && NavigationOriginPolicy.classify(candidate, isNewSession = false) == NavigationDecision.ALLOW_IN_SESSION -> candidate
            current is SessionUiState.Loading -> current.activeUrl
            current is SessionUiState.Active -> current.activeUrl
            else -> lastValidatedUrl
        }
        _uiState.value = SessionUiState.Active(
            activeUrl = activeUrl,
            canGoBack = canGoBack,
            popupOpen = popupOpen,
        )
    }

    fun onLoadError(uri: String?, categoryCode: Int, errorCode: Int) {
        val current = _uiState.value
        val candidateRetry = when {
            uri != null && IntentUriValidator.validateInboundUri(uri) is UriValidationResult.Valid ->
                (IntentUriValidator.validateInboundUri(uri) as UriValidationResult.Valid).normalizedUrl
            current is SessionUiState.Loading && IntentUriValidator.validateInboundUri(current.activeUrl) is UriValidationResult.Valid ->
                current.activeUrl
            else -> lastValidatedUrl
        }
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
        val current = _uiState.value
        val target = when (current) {
            is SessionUiState.Error -> {
                val valid = IntentUriValidator.validateInboundUri(current.retryUrl)
                if (valid is UriValidationResult.Valid) valid.normalizedUrl else IntentUriValidator.DEFAULT_ENTRY_URL
            }
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
        val current = _uiState.value
        if (current !is SessionUiState.Error && canGoBack) {
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
        val existing = pendingFilePromptId
        if (existing != null) {
            port.cancelFilePrompt(existing)
            telemetry.recordEvent(
                level = TelemetryLevel.ERROR,
                component = "RemoteSessionCoordinator",
                seamType = SeamType.DELTA_AUDIT_GAP,
                eventName = "prompt.file.callback_orphaned",
                ruleAc = "AC-6",
                attributes = mapOf("gap_type" to "checkpoint_skipped", "request_id" to existing),
            )
        }
        pendingFilePromptId = requestId
        _effects.tryEmit(
            UiEffect.LaunchDocumentPicker(
                requestId = requestId,
                mimeTypes = mimeTypes,
                allowMultiple = allowMultiple,
            ),
        )
    }

    fun onFilePickerResult(requestId: String, uris: List<String>?) {
        if (pendingFilePromptId != requestId) return
        pendingFilePromptId = null
        if (uris.isNullOrEmpty()) {
            port.cancelFilePrompt(requestId)
        } else {
            port.completeFilePrompt(requestId, uris)
        }
    }

    fun onSessionReset() {
        val existing = pendingFilePromptId ?: return
        pendingFilePromptId = null
        port.cancelFilePrompt(existing)
        telemetry.recordEvent(
            level = TelemetryLevel.ERROR,
            component = "RemoteSessionCoordinator",
            seamType = SeamType.DELTA_AUDIT_GAP,
            eventName = "prompt.file.callback_orphaned",
            ruleAc = "AC-6",
            attributes = mapOf("gap_type" to "checkpoint_skipped", "request_id" to existing),
        )
    }

    fun pendingFilePromptRequestId(): String? = pendingFilePromptId

    private fun transitionToError(
        kind: SessionErrorKind,
        message: String,
        retryUrl: String,
        eventName: String,
        attributes: Map<String, String>,
    ) {
        val beforeCount = telemetry.emittedCount()
        telemetry.recordEvent(
            level = TelemetryLevel.ERROR,
            component = "RemoteSessionCoordinator",
            seamType = SeamType.BOUNDARY_FAULT,
            eventName = eventName,
            ruleAc = "AC-7",
            attributes = attributes,
        )
        if (telemetry.emittedCount() == beforeCount) {
            telemetry.recordEvent(
                level = TelemetryLevel.ERROR,
                component = "RemoteSessionCoordinator",
                seamType = SeamType.DELTA_AUDIT_GAP,
                eventName = "error.ui.diagnostic_missing",
                ruleAc = "AC-7",
                attributes = mapOf("gap_type" to "side_effect_missing"),
            )
        }
        _uiState.value = SessionUiState.Error(
            kind = kind,
            message = message,
            retryUrl = retryUrl,
        )
    }

    private fun loadValidatedUrl(validatedUrl: String) {
        lastValidatedUrl = validatedUrl
        onValidatedUrlChanged(validatedUrl)
        _uiState.value = SessionUiState.Loading(activeUrl = validatedUrl, popupOpen = popupOpen)
        port.loadUri(validatedUrl)
    }

    private fun recordUriValidationOutcome(branch: String) {
        telemetry.recordEvent(
            level = if (branch == "Valid") TelemetryLevel.INFO else TelemetryLevel.WARN,
            component = "RemoteSessionCoordinator",
            seamType = SeamType.RULE_DIVERGENCE,
            eventName = "intent.uri.validation_outcome",
            ruleAc = "AC-4",
            attributes = mapOf("branch_taken" to branch),
        )
    }

    internal fun setStateForTest(state: SessionUiState) {
        _uiState.value = state
        if (state is SessionUiState.Active) {
            canGoBack = state.canGoBack
            popupOpen = state.popupOpen
            hasStarted = true
        } else if (state is SessionUiState.Loading) {
            popupOpen = state.popupOpen
            hasStarted = true
        }
    }
}

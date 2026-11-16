package com.antigravity.remote.session

import com.antigravity.remote.policy.IntentUriValidator
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

    fun onWarmIntent(intentUri: String?, hasIntentData: Boolean) {
        if (!hasIntentData || intentUri.isNullOrBlank()) {
            return
        }
        when (val validation = IntentUriValidator.validateInboundUri(intentUri)) {
            is UriValidationResult.Valid -> {
                telemetry.recordEvent(
                    level = TelemetryLevel.INFO,
                    component = "RemoteSessionCoordinator",
                    seamType = SeamType.RULE_DIVERGENCE,
                    eventName = "intent.uri.validation_outcome",
                    ruleAc = "AC-4",
                    attributes = mapOf("branch_taken" to "Valid"),
                )
                _uiState.value = SessionUiState.Loading(
                    activeUrl = validation.normalizedUrl,
                    popupOpen = popupOpen,
                )
                port.loadUri(validation.normalizedUrl)
            }
            is UriValidationResult.Rejected -> {
                telemetry.recordEvent(
                    level = TelemetryLevel.WARN,
                    component = "RemoteSessionCoordinator",
                    seamType = SeamType.RULE_DIVERGENCE,
                    eventName = "intent.uri.validation_outcome",
                    ruleAc = "AC-4",
                    attributes = mapOf("branch_taken" to "Rejected"),
                )
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

    fun onLoadStarted(uri: String?) {
        throw NotImplementedError("Not yet implemented")
    }

    fun onPageStop(uri: String?, success: Boolean) {
        throw NotImplementedError("Not yet implemented")
    }

    internal fun setStateForTest(state: SessionUiState) {
        _uiState.value = state
    }
}

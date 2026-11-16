package com.antigravity.remote.session

import com.antigravity.remote.telemetry.SeamTelemetry
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

    fun onWarmIntent(intentUri: String?, hasIntentData: Boolean) {
        if (!hasIntentData || intentUri.isNullOrBlank()) {
            return
        }
        throw NotImplementedError("Non-empty warm intent routing not yet implemented")
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

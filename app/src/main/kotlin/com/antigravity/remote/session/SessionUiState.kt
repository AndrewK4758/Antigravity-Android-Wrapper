package com.antigravity.remote.session

enum class SessionErrorKind {
    RUNTIME_INIT_FAILED,
    NETWORK_OR_LOAD_FAILED,
    INVALID_COLD_URI,
}

sealed interface SessionUiState {
    data object Initializing : SessionUiState
    data class Loading(val activeUrl: String, val popupOpen: Boolean = false) : SessionUiState
    data class Active(val activeUrl: String, val canGoBack: Boolean, val popupOpen: Boolean = false) : SessionUiState
    data class Error(val kind: SessionErrorKind, val message: String, val retryUrl: String) : SessionUiState
}

sealed interface UiEffect {
    data class OpenExternalUrl(val url: String) : UiEffect
    data class LaunchDocumentPicker(
        val requestId: String,
        val mimeTypes: List<String>,
        val allowMultiple: Boolean,
    ) : UiEffect
    data class ShowNonFatalNotice(val message: String) : UiEffect
    data object CloseApp : UiEffect
}

interface BrowserSessionPort {
    fun loadUri(url: String)
    fun reload()
    fun goBack()
    fun closePopup()
    fun completeFilePrompt(requestId: String, uris: List<String>)
    fun cancelFilePrompt(requestId: String)
}

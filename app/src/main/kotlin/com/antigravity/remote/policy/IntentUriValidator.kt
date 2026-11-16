package com.antigravity.remote.policy

import java.net.URI

enum class UriRejectionReason {
    EMPTY_URI,
    MALFORMED_URI,
    UNSUPPORTED_SCHEME,
    DISALLOWED_HOST,
    DISALLOWED_PATH,
}

sealed interface UriValidationResult {
    data class Valid(val normalizedUrl: String) : UriValidationResult
    data class Rejected(val reason: UriRejectionReason, val redactedTarget: String) : UriValidationResult
}

object IntentUriValidator {
    const val DEFAULT_ENTRY_URL: String = "https://antigravity.google.com/"
    private const val ALLOWED_HOST: String = "antigravity.google.com"

    fun validateInboundUri(rawUri: String?): UriValidationResult {
        val trimmed = rawUri?.trim().orEmpty()
        val parsed = URI(trimmed)
        val normalizedPath = if (parsed.rawPath.isNullOrEmpty()) "/" else parsed.rawPath
        val querySuffix = parsed.rawQuery?.let { "?$it" }.orEmpty()
        return UriValidationResult.Valid("https://$ALLOWED_HOST$normalizedPath$querySuffix")
    }
}

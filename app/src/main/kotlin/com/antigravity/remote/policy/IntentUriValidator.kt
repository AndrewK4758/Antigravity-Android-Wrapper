package com.antigravity.remote.policy

import java.net.URI
import java.net.URISyntaxException

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
        if (trimmed.isEmpty()) {
            return UriValidationResult.Rejected(UriRejectionReason.EMPTY_URI, "<empty>")
        }
        if (trimmed.any { it.isWhitespace() || it == '\\' }) {
            return UriValidationResult.Rejected(UriRejectionReason.MALFORMED_URI, redactForLog(trimmed))
        }

        val parsed = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return UriValidationResult.Rejected(UriRejectionReason.MALFORMED_URI, redactForLog(trimmed))
        }

        val scheme = parsed.scheme?.lowercase()
            ?: return UriValidationResult.Rejected(UriRejectionReason.MALFORMED_URI, redactForLog(trimmed))
        if (scheme != "https") {
            return UriValidationResult.Rejected(UriRejectionReason.UNSUPPORTED_SCHEME, redactForLog(trimmed))
        }

        val authority = parsed.rawAuthority
        val host = parsed.host?.lowercase()
        if (
            authority.isNullOrEmpty() ||
            authority.contains('@') ||
            parsed.rawUserInfo != null ||
            host.isNullOrEmpty() ||
            (parsed.port != -1 && parsed.port != 443)
        ) {
            return UriValidationResult.Rejected(UriRejectionReason.MALFORMED_URI, redactForLog(trimmed))
        }

        if (host != ALLOWED_HOST) {
            return UriValidationResult.Rejected(UriRejectionReason.DISALLOWED_HOST, redactForLog(trimmed))
        }

        val rawPath = parsed.rawPath.orEmpty()
        if (!isAllowedPath(rawPath)) {
            return UriValidationResult.Rejected(UriRejectionReason.DISALLOWED_PATH, redactForLog(trimmed))
        }

        val normalizedPath = if (rawPath.isEmpty()) "/" else rawPath
        val querySuffix = parsed.rawQuery?.let { "?$it" }.orEmpty()
        val fragmentSuffix = parsed.rawFragment?.let { "#$it" }.orEmpty()
        return UriValidationResult.Valid("https://$ALLOWED_HOST$normalizedPath$querySuffix$fragmentSuffix")
    }

    fun redactForLog(rawUri: String?): String {
        val trimmed = rawUri?.trim().orEmpty()
        if (trimmed.isEmpty()) return "<empty>"
        if (alreadyRedactedUrlRegex.matches(trimmed)) return trimmed
        return try {
            val parsed = URI(trimmed)
            val scheme = parsed.scheme?.lowercase() ?: return "<malformed>"
            val host = parsed.host?.lowercase()
            val path = parsed.rawPath.orEmpty().ifEmpty { if (host != null) "/" else "" }
            val queryKeys = parsed.rawQuery
                ?.split('&')
                ?.mapNotNull { it.substringBefore('=').takeIf(String::isNotBlank) }
                ?.joinToString(",")
                ?.takeIf(String::isNotEmpty)
                ?.let { "?keys=[$it]" }
                .orEmpty()
            if (host != null) "$scheme://$host$path$queryKeys" else "$scheme:<redacted>"
        } catch (_: URISyntaxException) {
            "<malformed>"
        }
    }

    private val alreadyRedactedUrlRegex = Regex("^https?://[^/?#\\s]+(/[^?#\\s]*)?\\?keys=\\[[^\\]]+]$")

    private fun isAllowedPath(rawPath: String): Boolean {
        if (rawPath.isEmpty() || rawPath == "/") return true
        val segments = rawPath.split('/')
        if (segments.any { it == ".." || it == "." }) return false
        return rawPath == "/r" || rawPath.startsWith("/r/")
    }
}

package com.antigravity.remote.policy

import java.net.URI
import java.net.URISyntaxException

enum class NavigationDecision {
    ALLOW_IN_SESSION,
    ALLOW_POPUP_IN_HOST,
    HAND_OFF_EXTERNAL,
    BLOCK,
}

object NavigationOriginPolicy {
    private val allowlistedHosts = setOf(
        "antigravity.google.com",
        "accounts.google.com",
    )

    fun classify(targetUri: String?, isNewSession: Boolean): NavigationDecision {
        val trimmed = targetUri?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it == '\\' }) {
            return NavigationDecision.BLOCK
        }
        val parsed = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return NavigationDecision.BLOCK
        }
        val scheme = parsed.scheme?.lowercase() ?: return NavigationDecision.BLOCK
        val host = parsed.host?.lowercase()
        val authority = parsed.rawAuthority
        if (
            scheme == "https" &&
            !host.isNullOrEmpty() &&
            !authority.isNullOrEmpty() &&
            !authority.contains('@') &&
            parsed.rawUserInfo == null &&
            (parsed.port == -1 || parsed.port == 443) &&
            host in allowlistedHosts
        ) {
            return if (isNewSession) {
                NavigationDecision.ALLOW_POPUP_IN_HOST
            } else {
                NavigationDecision.ALLOW_IN_SESSION
            }
        }
        return NavigationDecision.BLOCK
    }
}

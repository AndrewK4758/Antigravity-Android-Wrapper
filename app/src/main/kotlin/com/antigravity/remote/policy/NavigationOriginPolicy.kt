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
    private const val ACCOUNTS_HOST = "accounts.google.com"
    private val allowlistedHosts = setOf(
        "antigravity.google.com",
        ACCOUNTS_HOST,
    )

    fun classify(targetUri: String?, isNewSession: Boolean): NavigationDecision {
        val parsed = parseSafeWebUri(targetUri) ?: return NavigationDecision.BLOCK
        if (isAllowlistedHttps(parsed)) {
            return if (isNewSession) {
                NavigationDecision.ALLOW_POPUP_IN_HOST
            } else {
                NavigationDecision.ALLOW_IN_SESSION
            }
        }
        return NavigationDecision.HAND_OFF_EXTERNAL
    }

    fun isAccountsHost(targetUri: String?): Boolean {
        val parsed = parseSafeWebUri(targetUri) ?: return false
        return isAllowlistedHttps(parsed) && parsed.host?.lowercase() == ACCOUNTS_HOST
    }

    private fun parseSafeWebUri(targetUri: String?): URI? {
        val trimmed = targetUri?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it == '\\' }) {
            return null
        }
        val parsed = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = parsed.scheme?.lowercase() ?: return null
        val host = parsed.host?.lowercase()
        val authority = parsed.rawAuthority
        val validWebAuthority = (scheme == "https" || scheme == "http") &&
            !host.isNullOrEmpty() &&
            !authority.isNullOrEmpty() &&
            !authority.contains('@') &&
            parsed.rawUserInfo == null
        return if (validWebAuthority) parsed else null
    }

    private fun isAllowlistedHttps(parsed: URI): Boolean {
        val scheme = parsed.scheme?.lowercase()
        val host = parsed.host?.lowercase()
        return scheme == "https" && (parsed.port == -1 || parsed.port == 443) && host in allowlistedHosts
    }
}

package com.antigravity.remote

import com.antigravity.remote.policy.NavigationDecision
import com.antigravity.remote.policy.NavigationOriginPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationOriginPolicyTest {

    @Test
    fun s3_1_accounts_google_and_antigravity_stay_in_primary_session() {
        assertEquals(
            NavigationDecision.ALLOW_IN_SESSION,
            NavigationOriginPolicy.classify(
                targetUri = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fantigravity.google.com%2F",
                isNewSession = false,
            ),
        )
        assertEquals(
            NavigationDecision.ALLOW_IN_SESSION,
            NavigationOriginPolicy.classify(
                targetUri = "https://antigravity.google.com/r/inst-1",
                isNewSession = false,
            ),
        )
    }

    @Test
    fun s3_2_allowlisted_new_session_opens_in_managed_popup_host() {
        assertEquals(
            NavigationDecision.ALLOW_POPUP_IN_HOST,
            NavigationOriginPolicy.classify(
                targetUri = "https://accounts.google.com/o/oauth2/v2/auth",
                isNewSession = true,
            ),
        )
        assertEquals(
            NavigationDecision.ALLOW_POPUP_IN_HOST,
            NavigationOriginPolicy.classify(
                targetUri = "https://antigravity.google.com/r/inst-1",
                isNewSession = true,
            ),
        )
    }

    @Test
    fun s3_3_unrecognized_http_or_https_links_hand_off_externally() {
        assertEquals(
            NavigationDecision.HAND_OFF_EXTERNAL,
            NavigationOriginPolicy.classify(
                targetUri = "https://docs.python.org/3/",
                isNewSession = false,
            ),
        )
        assertEquals(
            NavigationDecision.HAND_OFF_EXTERNAL,
            NavigationOriginPolicy.classify(
                targetUri = "http://example.com/guide",
                isNewSession = true,
            ),
        )
    }

    @Test
    fun s3_4_non_http_schemes_and_userinfo_links_are_blocked() {
        val blocked = listOf(
            "intent://scan/#Intent;end",
            "javascript:void(0)",
            "file:///etc/passwd",
            "data:text/html,test",
            "https://user@docs.python.org/3/",
            "",
            null,
        )
        for (uri in blocked) {
            assertEquals(
                "Expected BLOCK for $uri",
                NavigationDecision.BLOCK,
                NavigationOriginPolicy.classify(targetUri = uri, isNewSession = false),
            )
        }
    }
}

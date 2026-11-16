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
}

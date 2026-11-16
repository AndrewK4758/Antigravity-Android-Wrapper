package com.antigravity.remote

import com.antigravity.remote.policy.IntentUriValidator
import com.antigravity.remote.policy.UriRejectionReason
import com.antigravity.remote.policy.UriValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentUriValidatorTest {

    @Test
    fun s2_1_valid_cold_action_view_preserves_instance_path_and_encoded_query() {
        val raw = "https://antigravity.google.com/r/dev-box?p=c%2Fabc123"

        val result = IntentUriValidator.validateInboundUri(raw)

        assertEquals(
            UriValidationResult.Valid("https://antigravity.google.com/r/dev-box?p=c%2Fabc123"),
            result,
        )
    }

    @Test
    fun s2_3_malformed_or_untrusted_cold_uris_are_rejected_with_exact_reasons() {
        val cases = listOf(
            "http://antigravity.google.com/r/inst-1" to UriRejectionReason.UNSUPPORTED_SCHEME,
            "javascript:alert(1)" to UriRejectionReason.UNSUPPORTED_SCHEME,
            "file:///data/local/tmp/x.html" to UriRejectionReason.UNSUPPORTED_SCHEME,
            "https://evil.example.com/r/inst-1" to UriRejectionReason.DISALLOWED_HOST,
            "https://antigravity.google.com.evil.com/r/inst-1" to UriRejectionReason.DISALLOWED_HOST,
            "https://user:secret@antigravity.google.com/r/inst-1?token=abc" to UriRejectionReason.MALFORMED_URI,
            "https://antigravity.google.com/admin/settings" to UriRejectionReason.DISALLOWED_PATH,
            "https://antigravity.google.com/r/../admin/settings" to UriRejectionReason.DISALLOWED_PATH,
        )

        for ((rawUri, expectedReason) in cases) {
            val result = IntentUriValidator.validateInboundUri(rawUri)
            assertTrue("Expected Rejected for $rawUri, got $result", result is UriValidationResult.Rejected)
            val rejected = result as UriValidationResult.Rejected
            assertEquals("Wrong rejection reason for $rawUri", expectedReason, rejected.reason)
            assertFalse("Secret leaked in redactedTarget for $rawUri", rejected.redactedTarget.contains("secret"))
            assertFalse("Query secret leaked in redactedTarget for $rawUri", rejected.redactedTarget.contains("abc"))
        }
    }
}

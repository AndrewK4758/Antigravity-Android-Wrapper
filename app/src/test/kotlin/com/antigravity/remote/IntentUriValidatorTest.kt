package com.antigravity.remote

import com.antigravity.remote.policy.IntentUriValidator
import com.antigravity.remote.policy.UriValidationResult
import org.junit.Assert.assertEquals
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
}

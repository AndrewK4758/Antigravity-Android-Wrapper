package com.antigravity.remote.session

import com.antigravity.remote.telemetry.SeamTelemetry
import com.antigravity.remote.telemetry.SeamType
import com.antigravity.remote.telemetry.TelemetryLevel
import java.util.concurrent.atomic.AtomicBoolean

class RuntimeLifecycleGuard(
    private val telemetry: SeamTelemetry,
) {
    private val initialized = AtomicBoolean(false)

    fun markRuntimeInitialized() {
        if (!initialized.compareAndSet(false, true)) {
            telemetry.recordEvent(
                level = TelemetryLevel.ERROR,
                component = "RuntimeLifecycleGuard",
                seamType = SeamType.UNSOUND_CONDITION,
                eventName = "gecko.runtime.uninitialized_or_duplicate",
                ruleAc = "AC-2",
                attributes = mapOf(
                    "precondition" to "GeckoRuntime must initialize at most once per process",
                    "expected_state" to "uninitialized",
                    "actual_state" to "already_initialized",
                    "code_location" to "RuntimeLifecycleGuard:markRuntimeInitialized",
                ),
            )
            throw IllegalStateException("GeckoRuntime already initialized")
        }
    }

    fun requireRuntimeInitializedForSession() {
        if (!initialized.get()) {
            telemetry.recordEvent(
                level = TelemetryLevel.ERROR,
                component = "RuntimeLifecycleGuard",
                seamType = SeamType.UNSOUND_CONDITION,
                eventName = "gecko.runtime.uninitialized_or_duplicate",
                ruleAc = "AC-2",
                attributes = mapOf(
                    "precondition" to "GeckoRuntime must initialize before opening GeckoSession",
                    "expected_state" to "initialized",
                    "actual_state" to "uninitialized",
                    "code_location" to "RuntimeLifecycleGuard:requireRuntimeInitializedForSession",
                ),
            )
            throw IllegalStateException("GeckoRuntime is not initialized")
        }
    }
}

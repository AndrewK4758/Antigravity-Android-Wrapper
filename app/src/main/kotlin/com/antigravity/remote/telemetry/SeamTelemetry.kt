package com.antigravity.remote.telemetry

enum class SeamType {
    UNSOUND_CONDITION,
    DELTA_AUDIT_GAP,
    RULE_DIVERGENCE,
    BOUNDARY_FAULT,
}

enum class TelemetryLevel {
    INFO,
    WARN,
    ERROR,
}

data class SeamEvent(
    val timestamp: String,
    val level: TelemetryLevel,
    val traceId: String,
    val spanId: String,
    val requestId: String,
    val service: String,
    val component: String,
    val seamType: SeamType,
    val eventName: String,
    val ruleAc: String,
    val attributes: Map<String, String> = emptyMap(),
)

interface SeamTelemetrySink {
    fun emit(jsonLine: String, event: SeamEvent)
}

class SeamTelemetry(
    private val sink: SeamTelemetrySink = NoOpTelemetrySink,
) {
    fun recordEvent(
        level: TelemetryLevel,
        component: String,
        seamType: SeamType,
        eventName: String,
        ruleAc: String,
        attributes: Map<String, String> = emptyMap(),
    ) {
        // Implemented incrementally as telemetry and observability scenarios execute.
    }

    private object NoOpTelemetrySink : SeamTelemetrySink {
        override fun emit(jsonLine: String, event: SeamEvent) = Unit
    }
}

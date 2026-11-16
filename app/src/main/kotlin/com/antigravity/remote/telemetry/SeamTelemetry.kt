package com.antigravity.remote.telemetry

import com.antigravity.remote.policy.IntentUriValidator
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    private val serviceName: String = "antigravity-remote-android",
) {
    private val totalEmitted = AtomicLong(0L)
    private val ruleCounters = ConcurrentHashMap<String, AtomicLong>()

    fun recordEvent(
        level: TelemetryLevel,
        component: String,
        seamType: SeamType,
        eventName: String,
        ruleAc: String,
        attributes: Map<String, String> = emptyMap(),
    ): SeamEvent {
        val sanitized = sanitizeAttributes(attributes)
        val event = SeamEvent(
            timestamp = Instant.now().toString(),
            level = level,
            traceId = UUID.randomUUID().toString().replace("-", ""),
            spanId = UUID.randomUUID().toString().replace("-", "").take(16),
            requestId = UUID.randomUUID().toString(),
            service = serviceName,
            component = component,
            seamType = seamType,
            eventName = eventName,
            ruleAc = ruleAc,
            attributes = sanitized,
        )
        if (seamType == SeamType.RULE_DIVERGENCE) {
            val branch = event.attributes["branch_taken"] ?: "default"
            ruleCounters.computeIfAbsent("$eventName:$branch") { AtomicLong(0L) }.incrementAndGet()
        }
        val json = toJsonLine(event)
        val delivered = runCatching {
            sink.emit(json, event)
        }.isSuccess
        if (delivered) {
            totalEmitted.incrementAndGet()
        }
        return event
    }

    private fun sanitizeAttributes(attributes: Map<String, String>): Map<String, String> {
        if (attributes.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for ((rawKey, rawVal) in attributes) {
            val keyLower = rawKey.lowercase()
            val cleanVal = when {
                sensitiveKeyTokens.any { keyLower.contains(it) } -> "[REDACTED]"
                looksLikeUrl(rawVal) -> IntentUriValidator.redactForLog(rawVal)
                else -> redactInlineSecrets(rawVal)
            }
            out[rawKey] = cleanVal
        }
        return out
    }

    private fun looksLikeUrl(value: String): Boolean {
        val lower = value.trim().lowercase()
        return lower.startsWith("https://") ||
            lower.startsWith("http://") ||
            lower.startsWith("intent://") ||
            lower.startsWith("file://") ||
            lower.startsWith("javascript:") ||
            lower.startsWith("data:")
    }

    private fun redactInlineSecrets(value: String): String {
        var sanitized = inlineUrlRegex.replace(value) { IntentUriValidator.redactForLog(it.value) }
        sanitized = inlineAuthRegex.replace(sanitized) { "${it.groupValues[1]}=[REDACTED]" }
        return sanitized
    }

    private companion object {
        val sensitiveKeyTokens = listOf(
            "cookie",
            "token",
            "secret",
            "password",
            "passwd",
            "authorization",
            "credential",
            "bearer",
            "session_id",
        )
        val inlineUrlRegex = Regex("https?://\\S+")
        val inlineAuthRegex = Regex("(?i)\\b(cookie|set-cookie|authorization|token|secret|password|sid|hsid|lsid)\\s*[:=]\\s*[^;\\s]+")
    }

    fun emittedCount(): Long = totalEmitted.get()

    fun ruleBranchCount(eventName: String, branchTaken: String): Long =
        ruleCounters["$eventName:$branchTaken"]?.get() ?: 0L

    private fun toJsonLine(event: SeamEvent): String {
        val fields = linkedMapOf(
            "timestamp" to event.timestamp,
            "level" to event.level.name,
            "trace_id" to event.traceId,
            "span_id" to event.spanId,
            "request_id" to event.requestId,
            "service" to event.service,
            "component" to event.component,
            "seam_type" to event.seamType.name,
            "event_name" to event.eventName,
            "requirement_id" to event.ruleAc,
        )
        for ((k, v) in event.attributes) {
            fields[k] = v
        }
        return fields.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
        }
    }

    private fun escapeJson(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    private object NoOpTelemetrySink : SeamTelemetrySink {
        override fun emit(jsonLine: String, event: SeamEvent) = Unit
    }
}

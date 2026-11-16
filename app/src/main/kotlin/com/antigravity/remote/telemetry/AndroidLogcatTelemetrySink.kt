package com.antigravity.remote.telemetry

import android.util.Log

class AndroidLogcatTelemetrySink : SeamTelemetrySink {
    override fun emit(jsonLine: String, event: SeamEvent) {
        when (event.level) {
            TelemetryLevel.INFO -> Log.i(TAG, jsonLine)
            TelemetryLevel.WARN -> Log.w(TAG, jsonLine)
            TelemetryLevel.ERROR -> Log.e(TAG, jsonLine)
        }
    }

    private companion object {
        const val TAG = "AntigravityRemoteSeam"
    }
}

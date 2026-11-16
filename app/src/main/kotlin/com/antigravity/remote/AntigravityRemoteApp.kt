package com.antigravity.remote

import android.app.Application
import com.antigravity.remote.session.RuntimeLifecycleGuard
import com.antigravity.remote.telemetry.AndroidLogcatTelemetrySink
import com.antigravity.remote.telemetry.SeamTelemetry
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File

class AntigravityRemoteApp : Application() {

    val telemetry: SeamTelemetry by lazy { SeamTelemetry(AndroidLogcatTelemetrySink()) }
    val runtimeGuard: RuntimeLifecycleGuard by lazy { RuntimeLifecycleGuard(telemetry) }

    @Volatile
    private var geckoRuntime: GeckoRuntime? = null

    fun profileDirectory(): File = File(filesDir, "mozilla")

    fun ensureGeckoRuntime(): Result<GeckoRuntime> {
        geckoRuntime?.let { return Result.success(it) }
        return synchronized(this) {
            geckoRuntime?.let { return@synchronized Result.success(it) }
            try {
                val settings = GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
                    .loginAutofillEnabled(false)
                    .webManifest(true)
                    .consoleOutput(false)
                    .build()
                val created = GeckoRuntime.create(this, settings)
                runtimeGuard.markRuntimeInitialized()
                geckoRuntime = created
                Result.success(created)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    fun requireRuntimeForSession(): GeckoRuntime {
        runtimeGuard.requireRuntimeInitializedForSession()
        return checkNotNull(geckoRuntime) { "GeckoRuntime unexpectedly null" }
    }
}

package com.antigravity.remote

import com.antigravity.remote.session.BrowserSessionPort
import com.antigravity.remote.telemetry.SeamEvent
import com.antigravity.remote.telemetry.SeamTelemetrySink

class RecordingBrowserSessionPort : BrowserSessionPort {
    val loadedUrls = mutableListOf<String>()
    var reloadCount = 0
    var goBackCount = 0
    var closePopupCount = 0
    val completedPrompts = mutableListOf<Pair<String, List<String>>>()
    val cancelledPrompts = mutableListOf<String>()

    override fun loadUri(url: String) {
        loadedUrls.add(url)
    }

    override fun reload() {
        reloadCount++
    }

    override fun goBack() {
        goBackCount++
    }

    override fun closePopup() {
        closePopupCount++
    }

    override fun completeFilePrompt(requestId: String, uris: List<String>) {
        completedPrompts.add(requestId to uris)
    }

    override fun cancelFilePrompt(requestId: String) {
        cancelledPrompts.add(requestId)
    }
}

class RecordingTelemetrySink : SeamTelemetrySink {
    val jsonLines = mutableListOf<String>()
    val events = mutableListOf<SeamEvent>()

    override fun emit(jsonLine: String, event: SeamEvent) {
        jsonLines.add(jsonLine)
        events.add(event)
    }
}

package com.antigravity.remote.ui

import com.antigravity.remote.session.RemoteSessionCoordinator

object ExternalActivityRouter {
    fun launchExternalUrl(
        url: String,
        coordinator: RemoteSessionCoordinator,
        launcher: (String) -> Unit,
    ): Boolean {
        return try {
            launcher(url)
            true
        } catch (_: RuntimeException) {
            coordinator.emitNotice("No compatible browser found to open external link")
            false
        }
    }
}

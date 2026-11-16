package com.antigravity.remote.ui

interface SessionHostViewPort<T> {
    fun currentAttachedSession(): T?
    fun releaseAttachedSession()
    fun attachSession(session: T)
}

object GeckoViewAttachmentHelper {
    fun <T : Any> rebindIfNeeded(host: SessionHostViewPort<T>, target: T?): Boolean {
        val current = host.currentAttachedSession()
        if (target == null) {
            if (current != null) {
                host.releaseAttachedSession()
            }
            return false
        }
        if (current === target) {
            return true
        }
        if (current != null) {
            host.releaseAttachedSession()
        }
        host.attachSession(target)
        return host.currentAttachedSession() === target
    }

    fun <T : Any> detachOnStop(host: SessionHostViewPort<T>) {
        if (host.currentAttachedSession() != null) {
            host.releaseAttachedSession()
        }
    }
}

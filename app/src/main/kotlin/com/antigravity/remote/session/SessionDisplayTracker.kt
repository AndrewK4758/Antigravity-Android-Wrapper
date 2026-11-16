package com.antigravity.remote.session

/**
 * Tracks the active primary and popup sessions separately from popup creation,
 * ensuring a newly created popup session is only bound to a host view once opened.
 */
class SessionDisplayTracker<T : Any> {
    private var primary: T? = null
    private var pendingPopup: T? = null
    private var activePopup: T? = null

    fun primarySession(): T? = primary

    fun pendingPopupSession(): T? = pendingPopup

    fun hasPopupSession(session: T): Boolean = session === pendingPopup || session === activePopup

    fun currentDisplay(): T? = activePopup ?: primary

    fun onPrimaryCreated(session: T) {
        primary = session
    }

    fun onPopupCreated(session: T) {
        pendingPopup = session
        activePopup = null
    }

    fun activatePopupIfMatches(session: T, isOpen: Boolean): Boolean {
        if (session !== pendingPopup || !isOpen) {
            return false
        }
        activePopup = session
        pendingPopup = null
        return true
    }

    fun clearPopup(): T? {
        val current = activePopup ?: pendingPopup
        activePopup = null
        pendingPopup = null
        return current
    }

    fun clearPrimary(): T? {
        val current = primary
        primary = null
        return current
    }

    fun clearAll(): List<T> = buildList {
        clearPopup()?.let(::add)
        clearPrimary()?.let { session ->
            if (none { it === session }) add(session)
        }
    }
}

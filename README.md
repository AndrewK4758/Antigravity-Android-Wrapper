# Antigravity Remote Android

Native Android wrapper application for **Google Antigravity Remote Control** (`https://antigravity.google.com`), built around an embedded **Mozilla GeckoView** browser engine (`org.mozilla.geckoview`) so remote sessions run in a dedicated standalone Android app rather than inside Chrome tabs.

## Why GeckoView

Antigravity Remote Control authenticates through Google Account sign-in (`https://accounts.google.com`), which blocks embedded Android System `WebView` components (`disallowed_useragent`) and does not publish `.well-known/assetlinks.json` for full-screen Trusted Web Activities. Embedding Mozilla GeckoView provides an Electron-style standalone browser runtime, persistent cookie/storage profile, and deep-link handling without Chrome tabs or browser chrome.

## Target Environment

- **Language / Build:** Kotlin + Gradle Kotlin DSL
- **Android SDK:** Compile/Target SDK 37, Min SDK 33 (`platforms/android-37.0`)
- **Embedded Engine:** `org.mozilla.geckoview:geckoview`
- **Entry URL:** `https://antigravity.google.com` and `/r/<instance-id>` deep links

## Repository Status

Repository initialized. Application module scaffolding and implementation will follow design and test planning.

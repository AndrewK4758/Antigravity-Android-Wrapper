# Antigravity Remote Android

Native Android wrapper application for **Google Antigravity Remote Control** (`https://antigravity.google.com`), built around an embedded **Mozilla GeckoView** browser engine (`org.mozilla.geckoview`) so remote sessions run in a dedicated standalone Android app rather than inside Chrome tabs.

## Why GeckoView

Antigravity Remote Control authenticates through Google Account sign-in (`https://accounts.google.com`), which blocks embedded Android System `WebView` components (`disallowed_useragent`) and does not publish `.well-known/assetlinks.json` for full-screen Trusted Web Activities. Embedding Mozilla GeckoView provides an Electron-style standalone browser runtime, persistent cookie/storage profile, and deep-link handling without Chrome tabs or browser chrome.

## Target Environment

- **Language / Build:** Kotlin + Gradle Kotlin DSL (`gradle-9.6.0-bin.zip`, AGP `9.4.0`, Java 17 bytecode)
- **Android SDK:** Compile SDK `37.2` (`platforms/android-37.2`), Target SDK `37`, Min SDK `29` (Android 10 through Android 17 / Samsung One UI devices, `arm64-v8a` & `x86_64`)
- **Embedded Engine:** `org.mozilla.geckoview:geckoview:160.0.20261104181552`
- **Entry URL:** `https://antigravity.google.com` and `/r/<instance-id>` deep links

## Build & Verify

```bash
export JAVA_HOME=/home/ak/.local/share/JetBrains/Toolbox/apps/android-studio/jbr
export ANDROID_HOME=/home/ak/Android/Sdk
./gradlew test assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

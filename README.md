# Whisproid

Android 11+ dictation bubble for Gemini 3.5 Transcribe Live. This independent project is inspired by Wispr Flow's Android interaction, and is not affiliated with Wispr.

## Use

1. Install the APK. In Settings, enter a Google AI Studio API key.
2. Tap **Enable dictation** while Whisproid is open; grant microphone permission. This starts a persistent, visible microphone-capable foreground service. It records only during dictation.
3. Enable **Whisproid** under Android Accessibility settings. A small movable translucent bubble appears when an ordinary text editor has input focus. Password, number and phone fields are excluded.
4. Hold the bubble, speak and release to submit. Or tap the bubble to start, then tap **✓** to submit or **×** to cancel. A successful transcript is inserted at the cursor and saved in local history. Tap **Disable dictation** in the app to stop the ready service and notification.

The bubble's size, opacity and shape (orb, pill, soft square), plus light/dark mode, can be changed in Settings. Drag the idle bubble to move it. The bubble animates while recording and changes to a spinner while Gemini finalizes text. If the service is stopped by Android, reopen Whisproid and tap Enable dictation to restart it. Settings has a shortcut to Android's App info page; on devices that aggressively stop background apps, optionally select Unrestricted under Battery. This can increase battery use and does not override a user-initiated stop or every OEM policy.

## Data and permissions

The key is encrypted at rest using Android Keystore AES-GCM and is sent only to Google's Gemini WebSocket endpoint during an active transcription. Raw microphone PCM is streamed directly to Google during recording, with no developer server. The app does not persist audio. Completed text is stored in an app-private SQLite database until deleted in History. Android backup is disabled. The app cannot guarantee the secrecy of a user-supplied key from a compromised or rooted device, nor hide it from Google, which must authenticate the request. Google's use and billing terms apply to your key.

Accessibility access detects focused editable fields and inserts submitted text. It does not harvest text from other apps. It uses `TYPE_ACCESSIBILITY_OVERLAY`, which does not require the general Display Over Other Apps permission. Insertion first attempts `ACTION_SET_TEXT` with cursor preservation; on editors that reject it, the app tries Android's clipboard paste action, which can leave the transcript on the system clipboard. Some custom editors and secure apps may reject both; the transcript is still in local History. On Android 14+, microphone foreground services started while an app is backgrounded cannot generally access a while-in-use microphone permission, so Enable dictation must be tapped in the visible app before using the bubble.

## Build

Use JDK 17, Android SDK platform/build tools 35 and Gradle 8.13:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

CI builds a non-debuggable release APK and requires a persistent privately managed signing key. Configure four repository Actions secrets: `ANDROID_SIGNING_KEYSTORE_BASE64` (base64 of the entire PKCS12/JKS keystore file), `ANDROID_SIGNING_KEY_ALIAS`, `ANDROID_SIGNING_STORE_PASSWORD`, and `ANDROID_SIGNING_KEY_PASSWORD`. Back up the keystore and passwords securely outside GitHub; losing them prevents future in-place updates. The workflow never creates a replacement key or publishes an unsigned/test-signed APK as production. It verifies the signed APK and publishes v0.2.4 only after required checks pass. The release variant excludes the debug test receiver and synthetic audio asset. No API key is embedded in either APK. For local development, `./gradlew :app:assembleDebug` creates `app/build/outputs/apk/debug/app-debug.apk`.

The production signing certificate SHA-256 is `74:B6:AF:B6:8D:2D:58:AB:2D:59:A5:FB:07:DB:A3:18:15:8F:D1:7C:57:7B:04:65:93:3D:58:95:03:3B:78:58`; CI refuses a different certificate. Keep using the original keystore for subsequent versions.

If you do not have a long-lived signing key, create one on a trusted machine outside this repository with `keytool -genkeypair -keystore whisproid-signing.p12 -storetype PKCS12 -alias whisproid -keyalg RSA -keysize 3072 -validity 10000`. Enter and back up a strong password; for PKCS12, the key password is commonly the same as the store password. Encode the file with `base64 -w0 whisproid-signing.p12` (on macOS, `base64 < whisproid-signing.p12 | tr -d '\n'`) and paste the result only into the `ANDROID_SIGNING_KEYSTORE_BASE64` Actions secret. Never put the keystore, passwords, or encoded data in a commit, issue, CI log, or public artifact. After setting all four secrets, run the **Android build** workflow on `main` manually; it will wait for the same commit's successful emulator/Gemini checks and publish the signed APK.

Android accepts an in-place APK update only with the same application ID, the same signing certificate (or valid rotation proof), and a nondecreasing versionCode. v0.2.0 introduced the stable certificate with versionCode 4; v0.2.1 through v0.2.4 use the same certificate and increment from codes 5 through 8, so v0.2.4 updates the stable-signed builds in place without clearing app data. Future releases must keep incrementing the code and reuse the same key. Earlier v0.1.x releases used different ephemeral CI keys, so they **cannot** update in place. Copy any transcript history you want to keep, uninstall that test build (which deletes its app data), then install v0.2.4 once. Stable signing establishes update identity, but does not guarantee Play Protect will trust a sideloaded APK or remove its installation warning.

GitHub Actions emulator coverage targets Android 11 (API 30), Android 15 (API 35) and Android 16 (API 36). Instrumentation tests cover app navigation, encrypted key round trip, history persistence/deletion, and capture home, settings and history screenshots. Cross-app editor insertion and screenshots have been exercised on these emulator versions. A secret-backed test verifies real Gemini Live transcription of synthetic audio and confirms that the server responds with binary WebSocket frames.

A separate secret-backed Android 15 emulator pilot passed the complete app-to-Gemini-to-external-editor path. In debug builds only, a synthetic speech asset substitutes for microphone samples while `AudioRecord` reads pace the capture loop; the app sends those frames to Gemini Live, inserts the recognized phrase into a focused editor, and stores it in History. The published release APK excludes this test receiver and asset. A user also confirmed the basic microphone-to-editor workflow on a physical device; Android 11/15/16 physical-device matrix and OEM background behavior remain unverified.

The editor detection searches focused editable nodes in application window trees when a custom editor omits normal focus lookup. Empty-field placeholders are excluded from inserted text when exposed as a hint. WhatsApp and Telegram may expose their placeholder as ordinary accessibility text without hint metadata, so v0.2.4 uses their native paste action at the cursor. This leaves the transcript on the system clipboard; if paste fails, the transcript remains in History. Physical-device behavior in WhatsApp, Telegram, ChatGPT and Claude still requires confirmation.

## Known limits

- Android system overlay behavior and third-party editor support vary by device. The app has not been verified across physical Android 11, 15 and 16 devices. System-managed sticky service restart can improve recovery from memory pressure; OEM battery policies and user stops can still terminate the app. A microphone foreground service cannot be arbitrarily launched from the background on Android 14+.
- A dictation can run for at most 9 minutes 45 seconds, below the model's 10-minute Live session limit. A connection that fails before final text produces an error; audio is not retained for retry.
- Audio is sent as 40 ms PCM chunks (previously 100 ms). The app uses only `gemini-3.5-transcribe-live` in SMART mode; there is no secondary language-model cleanup or editing request. The client waits briefly after final segments to preserve trailing text; turn completion can end the wait sooner. Audio is already sent during speech rather than uploaded on release. Network and model processing still determine most perceived latency.
- API and quota availability depend on the user's AI Studio project. A successful CI test with the repository secret cannot guarantee that another key, quota, phone, or editor will behave identically.

## References

- [Gemini 3.5 Live transcription](https://ai.google.dev/gemini-api/docs/live-api/live-transcribe)
- [Gemini Live WebSocket protocol](https://ai.google.dev/api/live)
- [Android accessibility services](https://developer.android.com/guide/topics/ui/accessibility/service)
- [Android microphone foreground restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Wispr Flow Android setup](https://docs.wisprflow.ai/articles/8858845757-setup-wispr-flow-on-android-android-settings)
- [Wispr Flow bubble customization](https://docs.wisprflow.ai/articles/2807859589-customize-flow-bubble-size-and-shrink-behavior-on-android)

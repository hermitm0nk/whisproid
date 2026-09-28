# Whisproid

Android 11+ dictation bubble for Gemini 3.5 Transcribe Live. This independent project is inspired by Wispr Flow's Android interaction, and is not affiliated with Wispr.

## Use

1. Install the APK. In Settings, enter a Google AI Studio API key.
2. Tap **Enable dictation** while Whisproid is open; grant microphone permission. This starts a persistent, visible microphone-capable foreground service. It records only during dictation.
3. Enable **Whisproid** under Android Accessibility settings. A small movable translucent bubble appears when an ordinary text editor has input focus. Password, number and phone fields are excluded.
4. Hold the bubble, speak and release to submit. Or tap the bubble to start, then tap **✓** to submit or **×** to cancel. A successful transcript is inserted at the cursor and saved in local history. Tap **Disable dictation** in the app to stop the ready service and notification.

The bubble's size, opacity and shape (orb, pill, soft square), plus light/dark mode, can be changed in Settings. Drag the idle bubble to move it. If the service is stopped by Android, reopen Whisproid and tap Enable dictation to restart it.

## Data and permissions

The key is encrypted at rest using Android Keystore AES-GCM and is sent only to Google's Gemini WebSocket endpoint during an active transcription. Raw microphone PCM is streamed directly to Google during recording, with no developer server. The app does not persist audio. Completed text is stored in an app-private SQLite database until deleted in History. Android backup is disabled. The app cannot guarantee the secrecy of a user-supplied key from a compromised or rooted device, nor hide it from Google, which must authenticate the request. Google's use and billing terms apply to your key.

Accessibility access detects focused editable fields and inserts submitted text. It does not harvest text from other apps. It uses `TYPE_ACCESSIBILITY_OVERLAY`, which does not require the general Display Over Other Apps permission. Insertion first attempts `ACTION_SET_TEXT` with cursor preservation; on editors that reject it, the app tries Android's clipboard paste action, which can leave the transcript on the system clipboard. Some custom editors and secure apps may reject both; the transcript is still in local History. On Android 14+, microphone foreground services started while an app is backgrounded cannot generally access a while-in-use microphone permission, so Enable dictation must be tapped in the visible app before using the bubble.

## Build

Use JDK 17, Android SDK platform/build tools 35 and Gradle 8.13:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk`. The debug APK is test-signed; it is installable, but signatures generated on different machines differ, so uninstall before installing a build signed elsewhere. No API key is embedded in the APK. For a production release, configure a persistent private release signing key outside the repository.

GitHub Actions emulator coverage targets Android 11 (API 30), Android 15 (API 35) and Android 16 (API 36). Instrumentation tests cover app navigation, encrypted key round trip, history persistence/deletion, and capture home, settings and history screenshots. Cross-app editor insertion and screenshots have been exercised on these emulator versions. A secret-backed test verifies real Gemini Live transcription of synthetic audio and confirms that the server responds with binary WebSocket frames.

A separate secret-backed Android 15 emulator pilot passed the complete app-to-Gemini-to-external-editor path. A debug-only synthetic speech asset substitutes for microphone samples while `AudioRecord` reads pace the capture loop; the app sends those frames to Gemini Live, inserts the recognized phrase into a focused editor, and stores it in History. This verifies the integration path, not physical microphone input. Physical microphone capture and behavior on physical devices have not been verified.

## Known limits

- Android system overlay behavior and third-party editor support vary by device. The app has not yet been verified on physical Android 11, 15 and 16 devices; physical microphone capture is unverified.
- A dictation can run for at most 9 minutes 45 seconds, below the model's 10-minute Live session limit. A connection that fails before final text produces an error; audio is not retained for retry.
- API and quota availability depend on the user's AI Studio project. A successful CI test with the repository secret cannot guarantee that another key, quota, phone, or editor will behave identically.

## References

- [Gemini 3.5 Live transcription](https://ai.google.dev/gemini-api/docs/live-api/live-transcribe)
- [Gemini Live WebSocket protocol](https://ai.google.dev/api/live)
- [Android accessibility services](https://developer.android.com/guide/topics/ui/accessibility/service)
- [Android microphone foreground restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Wispr Flow Android setup](https://docs.wisprflow.ai/articles/8858845757-setup-wispr-flow-on-android-android-settings)
- [Wispr Flow bubble customization](https://docs.wisprflow.ai/articles/2807859589-customize-flow-bubble-size-and-shrink-behavior-on-android)

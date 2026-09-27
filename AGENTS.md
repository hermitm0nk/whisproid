# Repository instructions

- This is a native Kotlin Android app. Target Android 11+ (minSdk 30); compile and test with JDK 17 and `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
- Keep the user-provided API key on device, encrypted with Android Keystore. Do not commit secrets, API keys, audio, history databases, signing keys or private user content.
- Preserve the direct WebSocket path to `gemini-3.5-transcribe-live`; there is no developer-operated backend.
- The overlay must be nonfocusable and shown only with a focused editable text node. Exclude passwords and nontext editors. Never insert after cancel or into a changed target.
- `MicrophoneService` is launched from a visible Activity before background microphone use. Account for Android 14–16 foreground service restrictions. Do not introduce background launches without validating the platform behavior.
- Retain completed transcripts locally even when insertion fails. Test cursor insertion, network/session cleanup, permission handling and overlay state changes when modifying those paths.
- Keep README and PLAN updated when behavior or test evidence changes. Label untested device behavior explicitly.

# Plan and progress

## 0.1.2 scope

- [x] Research Wispr's Android bubble, Gemini 3.5 Transcribe Live WebSocket protocol, and Android accessibility/microphone restrictions.
- [x] Build native Android project with local encrypted API key, app-private transcript history and theme/appearance controls.
- [x] Add accessibility focus detection, draggable overlay, hold and tap dictation controls, cancel, and cursor insertion.
- [x] Stream 16 kHz mono PCM directly to Gemini with manual activity start/end and Smart transcription.
- [x] Compile and run local unit tests (`:app:testDebugUnitTest :app:assembleDebug`, 2026-09-28); APK signature verified with `apksigner`.
- [ ] Install and validate on physical Android 11, 15 and 16; verify third-party text fields, interruption, clipboard fallback and OEM battery settings.
- [x] Publish source in `hermitm0nk/whisproid` and an installable test APK in the v0.1.0 GitHub Release.
- [x] Independent source review completed; corrected the reported defects and rebuilt with lint and unit tests.
- [x] Add GitHub Actions emulator coverage for Android 11 (API 30), Android 15 (API 35) and Android 16 (API 36): instrumentation navigation, encrypted key round trip, history persistence/deletion, and home/settings/history screenshots.
- [x] Verify synthetic audio transcription through the real Gemini Live API and observe binary server WebSocket frames using the repository secret.
- [x] Verify accessibility overlay behavior and cross-app editor insertion, with screenshots, on Android 11/15/16 emulators; apply and test the independent reviewer's production fixes.
- [x] Pass a secret-backed in-app emulator pilot: a debug-only synthesized PCM asset is paced by AudioRecord reads, streamed by the app to Gemini, then the returned text is inserted into an external editor and stored in History. This does not validate physical microphone input.
- [ ] Verify physical microphone capture and app behavior on physical Android 11, 15 and 16 devices.
- [x] Configure CI to build a non-debuggable release APK, sign it with a per-run ephemeral test key, verify the signature/package/debuggable flag, and publish it in an immutable commit-specific release. The debug APK artifact and debug-only tests remain available; the release package excludes the debug test receiver and synthetic audio asset.

## Release gates

Build, lint, unit tests, emulator UI screenshots, cross-app insertion, real Gemini binary-frame synthetic-audio transcription, and the combined app-to-Gemini-to-editor synthetic-audio pilot passed. Physical microphone capture and physical-device behavior remain unverified. The published release APK is non-debuggable but test-signed with an ephemeral key; every build has a different signing identity, so uninstall is required before installing another build. This key is not suitable for production or update continuity; production distribution needs a stable private signing key.

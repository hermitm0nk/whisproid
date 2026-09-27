# Plan and progress

## 0.1.1 scope

- [x] Research Wispr's Android bubble, Gemini 3.5 Transcribe Live WebSocket protocol, and Android accessibility/microphone restrictions.
- [x] Build native Android project with local encrypted API key, app-private transcript history and theme/appearance controls.
- [x] Add accessibility focus detection, draggable overlay, hold and tap dictation controls, cancel, and cursor insertion.
- [x] Stream 16 kHz mono PCM directly to Gemini with manual activity start/end and Smart transcription.
- [x] Compile and run local unit tests (`:app:testDebugUnitTest :app:assembleDebug`, 2026-09-28); APK signature verified with `apksigner`.
- [ ] Install and validate on physical Android 11, 15 and 16; verify third-party text fields, interruption, clipboard fallback and OEM battery settings.
- [x] Publish source in `hermitm0nk/whisproid` and an installable test APK in the v0.1.0 GitHub Release.
- [x] Independent source review completed; corrected the reported defects and rebuilt with lint and unit tests.
- [x] Run automated emulator smoke tests on Android 11, 15 and 16 (API 30, 35, 36; GitHub Actions matrix 2026-09-27).
- [ ] Publish the v0.1.1 APK with finalized-transcript handling and emulator test coverage.

## Release gates

Build, lint, unit tests and emulator smoke tests are automated. A physical-device and real-API test requires a phone and the user's AI Studio key; do not mark it passed based only on compilation. Debug signing is temporary; production updates need a stable signing key.

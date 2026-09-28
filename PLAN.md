# Plan and progress

## 0.2.0 stability, visuals and update continuity

- [x] User confirmed the basic microphone-to-editor workflow on a physical device; focus this iteration on the reported defects, not repeating that workflow.
- [x] Link to app battery settings with optional Unrestricted guidance; make the foreground microphone service sticky for system-managed memory-pressure recovery, with permission-safe failure on restricted restarts. OEM stops cannot be bypassed.
- [x] Draw an app-icon-like idle waveform, animated recording bars including hold, and a distinct animated spinner during submission/finalization.
- [x] Fix appearance radio selection; mask the saved API key except its last four characters, with a blank replacement editor and explicit clear.
- [x] Reduce PCM frames from 100 to 40 ms and final settling from two seconds to 0.5–0.7 seconds while keeping Gemini SMART final text. No separate parsing/editing request exists.
- [x] Replace per-run ephemeral release signing with required persistent GitHub Actions signing secrets; bump versionCode to 4.
- [ ] Configure the four signing secrets, build/verify the production signed APK in CI, and publish v0.2.0. Previous ephemeral test installs require one uninstall; future same-key versions can update.
- [x] Independent v0.2.0 review completed; fixed fork-PR signing-secrets failure and the 12-second delay for a final segment emitted before button release.
- [ ] Compile/lint/test the changes in CI; inspect new UI screenshots and release-signing outcome.

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
- [ ] Verify physical microphone capture and app behavior across physical Android 11, 15 and 16 devices (basic workflow confirmed by user on one real device).
- [x] Configure CI to build a non-debuggable release APK, sign it with a per-run ephemeral test key, verify the signature/package/debuggable flag, and publish it in an immutable commit-specific release. The debug APK artifact and debug-only tests remain available; the release package excludes the debug test receiver and synthetic audio asset.

## Release gates

Build, lint, unit tests, emulator UI screenshots, cross-app insertion, real Gemini binary-frame synthetic-audio transcription, and the combined app-to-Gemini-to-editor synthetic-audio pilot passed. Physical microphone capture and physical-device behavior remain unverified. The published release APK is non-debuggable but test-signed with an ephemeral key; every build has a different signing identity, so uninstall is required before installing another build. This key is not suitable for production or update continuity; production distribution needs a stable private signing key.

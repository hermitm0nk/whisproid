# Plan and progress

## 0.2.3 editor compatibility and completion latency

- [x] Search focused editor nodes in application window trees when `findFocus` misses a custom editor, while still rejecting password and nontext nodes.
- [x] Treat an exposed hint as empty editor content at an empty selection so the placeholder is not prepended to dictation.
- [x] Update size and opacity numbers as the sliders move, with an instrumentation check for labels and persisted values.
- [x] Confirm audio already streams in 40 ms chunks during recording. Shorten the post-release wait after a final segment or turn marker while retaining a settling window for trailing speech.
- [ ] Verify the release in CI and install over a stable-signed v0.2.1 on a physical device; third-party ChatGPT/Claude/WhatsApp/Telegram behavior depends on their accessibility trees.

## 0.2.2 stability, visuals and update continuity

- [x] User confirmed the basic microphone-to-editor workflow on a physical device; focus this iteration on the reported defects, not repeating that workflow.
- [x] Link to app battery settings with optional Unrestricted guidance; make the foreground microphone service sticky for system-managed memory-pressure recovery, with permission-safe failure on restricted restarts. OEM stops cannot be bypassed.
- [x] Draw an app-icon-like idle waveform, animated recording bars including hold, and a distinct animated spinner during submission/finalization.
- [x] Fix appearance radio selection; mask the saved API key except its last four characters, with a blank replacement editor and explicit clear.
- [x] Reduce PCM frames from 100 to 40 ms; keep a conservative two-second settling window for trailing SMART final segments, but complete faster (0.5 seconds) on an authoritative turn-complete event. No separate parsing/editing request exists.
- [x] Replace per-run ephemeral release signing with persistent GitHub Actions secrets. v0.2.0 (versionCode 4) is the first stable-signed production release; v0.2.1 (code 5) and v0.2.2 (code 6) use the same pinned certificate.
- [x] Configure the four signing secrets and publish v0.2.0/v0.2.1. Previous ephemeral test installs require one uninstall; v0.2.2 updates either stable build in place.
- [x] Independent v0.2.0 review completed; fixed fork-PR signing-secrets failure and the 12-second delay for a final segment emitted before button release.
- [x] Add CI screenshot capture of recording, hold, and transcribing overlay states without reading the accessibility hierarchy mid-session.
- [x] Secret-backed app-to-Gemini-to-editor tap/cancel, tap/submit, and hold/release test passed after pacing the synthetic phrase; Android 11/15/16 emulator matrix and Live API integration passed on `acdac61`. Recording and held-state screenshots show the waveform.
- [x] Latest CI passed all emulator and secret-backed Gemini tests. The screenshot immediately after Submit caught the old waveform for one frame, so switch the visible meter to spinner synchronously on Submit before the service callback.
- [x] Independently reviewed current release code again; corrected shape/opacity consistency and compact-screen recording controls, pinned the first production certificate SHA-256, and made existing-tag checks fail on mismatched commit or missing APK.
- [x] Publish the independent review fixes as signed v0.2.1 after all CI checks passed.
- [ ] Verify the transcribing spinner across a delayed recording callback with immediate and settled screenshots, then publish signed v0.2.2 after CI checks.

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

"""Full app audio path test using a debug-only synthetic PCM asset."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

APP = "dev.hermitm0nk.flowbubble"
TEST_APP = f"{APP}.test"
PHRASE = "today is monday and the sky is blue"
TEST_AUDIO_ACTION = f"{APP}.TEST_AUDIO"
TEST_BRIDGE = f"{APP}/{APP}.core.TestBridgeReceiver"


def adb(*args, timeout=30):
    """Run adb without printing command arguments, which may include the API key."""
    try:
        result = subprocess.run(
            ["adb", *args], text=True, capture_output=True, timeout=timeout
        )
    except subprocess.TimeoutExpired:
        # TimeoutExpired includes the full command (and thus possibly the key)
        # in its repr. Never let that detail reach the Actions log.
        raise RuntimeError("adb operation timed out") from None
    if result.returncode:
        raise RuntimeError(f"adb operation failed (exit {result.returncode})")
    return result.stdout


def ui_tree():
    adb("shell", "uiautomator", "dump", "/sdcard/whisproid-e2e.xml", timeout=20)
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/whisproid-e2e.xml"))


def bounds_center(node):
    numbers = list(map(int, re.findall(r"\d+", node.attrib.get("bounds", ""))))
    if len(numbers) != 4:
        raise RuntimeError("Could not read a visible control's bounds")
    left, top, right, bottom = numbers
    return (left + right) // 2, (top + bottom) // 2


def tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))


def tap_text(text):
    root = ui_tree()
    node = next((item for item in root.iter("node") if item.get("text") == text), None)
    if node is None:
        raise RuntimeError(f"Expected control is not visible: {text}")
    tap(*bounds_center(node))


def wait_for_node(predicate, timeout=10, interval=0.4):
    """Return a freshly-read UI node before the deadline, never retaining stale nodes."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = ui_tree()
        node = next((item for item in root.iter("node") if predicate(item)), None)
        if node is not None:
            return node
        time.sleep(interval)
    return None


def tap_text_when_visible(text, timeout=10):
    node = wait_for_node(lambda item: item.get("text") == text, timeout=timeout)
    if node is None:
        raise RuntimeError(f"Expected control is not visible: {text}")
    tap(*bounds_center(node))


def editor_visible_with_phrase():
    root = ui_tree()
    return any(node.get("package") == TEST_APP
               and node.get("class") == "android.widget.EditText"
               and PHRASE in (node.get("text") or "").lower()
               for node in root.iter("node"))


def external_editor_text():
    root = ui_tree()
    node = next((node for node in root.iter("node")
                 if node.get("package") == TEST_APP
                 and node.get("class") == "android.widget.EditText"), None)
    if node is None:
        raise RuntimeError("External editor was not visible")
    return node.get("text") or ""


def focused_external_editor():
    root = ui_tree()
    return any(node.get("package") == TEST_APP
               and node.get("class") == "android.widget.EditText"
               and node.get("focused") == "true"
               for node in root.iter("node"))


def history_contains_phrase():
    adb("shell", "am", "start", "-W", "-n", f"{APP}/{APP}.ui.MainActivity")
    history = wait_for_node(lambda node: (node.get("text") or "").startswith("Transcript history ("))
    if history is None:
        raise RuntimeError("Transcript history control was not visible")
    tap(*bounds_center(history))
    return wait_for_node(lambda node: node.get("package") == APP
                         and PHRASE in (node.get("text") or "").lower()) is not None


def install_and_prepare_app(api_key):
    # `adb shell input text` ultimately passes through a remote shell and its
    # own percent-to-space convention. Restrict the key to the documented
    # Google API-key alphabet so it cannot be interpreted as shell syntax or
    # transformed by Android's input command. Do not include the value in errors.
    if not re.fullmatch(r"[A-Za-z0-9_-]+", api_key):
        raise RuntimeError("Google API key contains characters unsupported by safe emulator text input")
    adb("install", "-r", "app/build/outputs/apk/debug/app-debug.apk", timeout=120)
    adb("install", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", timeout=120)
    adb("shell", "pm", "grant", APP, "android.permission.RECORD_AUDIO")
    adb("shell", "settings", "put", "secure", "enabled_accessibility_services",
        f"{APP}/{APP}.core.BubbleAccessibilityService")
    adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")

    adb("shell", "am", "start", "-W", "-n", f"{APP}/{APP}.ui.MainActivity", timeout=30)
    tap_text_when_visible("API key and bubble appearance", timeout=15)

    # The Settings panel can take a moment to attach its controls. Re-dump on
    # every poll so coordinates always come from the same, current hierarchy.
    deadline = time.monotonic() + 15
    field = save = None
    last_settings_tap = 0.0
    while time.monotonic() < deadline:
        root = ui_tree()
        field = next((item for item in root.iter("node")
                      if item.get("class") == "android.widget.EditText"), None)
        save = next((item for item in root.iter("node")
                     if item.get("text") == "Save API key"), None)
        if field is not None and save is not None:
            break
        home_settings = next((item for item in root.iter("node")
                              if item.get("text") == "API key and bubble appearance"), None)
        if home_settings is not None and time.monotonic() - last_settings_tap > 2:
            tap(*bounds_center(home_settings))
            last_settings_tap = time.monotonic()
        field = save = None
        time.sleep(0.4)
    if field is None or save is None:
        raise RuntimeError("Could not find the API key field and Save button")
    save_x, save_y = bounds_center(save)
    tap(*bounds_center(field))
    # The key is entered only into the password field and is never printed, dumped
    # to logs or captured in a screenshot/artifact.
    adb("shell", "input", "text", api_key)
    adb("shell", "input", "keyevent", "4")
    time.sleep(0.3)
    # Re-read Save after keyboard dismissal; old coordinates may no longer be valid.
    save = wait_for_node(lambda item: item.get("text") == "Save API key", timeout=5)
    if save is None:
        raise RuntimeError("Save API key button was not visible after dismissing the keyboard")
    save_x, save_y = bounds_center(save)
    tap(save_x, save_y)

    # Settings is an in-activity panel; Android Back would close MainActivity.
    # Use its explicit navigation control instead.
    back = wait_for_node(lambda item: item.get("content-desc") == "Back to home", timeout=10)
    if back is None:
        raise RuntimeError("Settings back-to-home control was not visible")
    tap(*bounds_center(back))
    if wait_for_node(lambda node: node.get("text") == "Ready to dictate", timeout=10) is None:
        raise RuntimeError("Encrypted API key was not saved or Back did not return to Home")
    # Start the microphone foreground service from this visible activity.
    tap_text("Enable dictation")
    start_output = adb("shell", "am", "start", "-n",
                       f"{TEST_APP}/dev.hermitm0nk.flowbubble.HostActivity",
                       "--ez", "focus", "false", timeout=30)
    try:
        deadline = time.monotonic() + 15
        focused = False
        while time.monotonic() < deadline:
            if focused_external_editor():
                focused = True
                break
            editor = wait_for_node(
                lambda node: node.get("package") == TEST_APP
                and node.get("text") == "Write a message", timeout=1
            )
            if editor is not None:
                tap(*bounds_center(editor))
            time.sleep(0.4)
        if not focused:
            raise RuntimeError("HostActivity does not expose a focused external EditText")
    except RuntimeError as error:
        raise RuntimeError(f"{error}; safe HostActivity start stdout: {start_output.strip()!r}") from None
    if diagnostic_code("TEST_STATUS") == 2:
        raise RuntimeError("Accessibility service is unavailable after HostActivity launch")


def display_size_and_density():
    size_output = adb("shell", "wm", "size")
    density_output = adb("shell", "wm", "density")
    sizes = re.findall(r"(\d+)x(\d+)", size_output)
    densities = re.findall(r"(?:Override|Physical) density:\s*(\d+)", density_output)
    if not sizes or not densities:
        raise RuntimeError("Could not determine emulator display geometry")
    width, height = map(int, sizes[-1])
    density = int(densities[-1]) / 160.0
    return width, height, density


def stage_synthetic_audio():
    output = adb("shell", "am", "broadcast", "-a", TEST_AUDIO_ACTION,
                 "-n", TEST_BRIDGE, "-p", APP, timeout=20)
    if "Broadcast completed: result=1" not in output:
        raise RuntimeError("Debug synthetic audio harness did not report successful asset staging")


def diagnostic_code(action):
    output = adb("shell", "am", "broadcast", "-a", f"{APP}.{action}",
                 "-n", TEST_BRIDGE, "-p", APP, timeout=20)
    match = re.search(r"Broadcast completed: result=(\d+)", output)
    return int(match.group(1)) if match else -1


def history_count():
    return diagnostic_code("TEST_HISTORY_COUNT") - 100


def wait_for_status(expected, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if diagnostic_code("TEST_STATUS") == expected:
            return True
        time.sleep(0.25)
    return False


def overlay_control_centers(width, density):
    # renderNow right-aligns the expanded horizontal layout. Child widths are
    # 58dp / 98dp / 58dp, with 5dp end margins on all three controls.
    cancel_x = width - int((58 + 5 + 98 + 5 + 58 + 5 - 29) * density)
    submit_x = width - int((29 + 5) * density)
    center_y = int((105 + 29) * density)
    return (cancel_x, center_y), (submit_x, center_y)


def tap_recording_bubble(bubble_x, bubble_y):
    if not focused_external_editor():
        raise RuntimeError("Refusing tap: external EditText is not focused")
    tap(bubble_x, bubble_y)
    if not wait_for_status(11, timeout=10):
        raise RuntimeError("Tap did not start the overlay recording session")
    # Recording state is visible through diagnostics before renderNow has
    # necessarily attached the expanded controls to the overlay.
    time.sleep(0.4)


def exercise_tap_cancel(bubble_x, bubble_y, cancel_center):
    baseline_history = history_count()
    baseline_text = external_editor_text()
    stage_synthetic_audio()
    tap_recording_bubble(bubble_x, bubble_y)
    # No UiAutomator hierarchy reads while recording: they compete with the
    # app's AccessibilityService connection and can cancel this session.
    tap(*cancel_center)
    if not wait_for_status(10, timeout=10):
        raise RuntimeError("Cancel tap did not return dictation to ready")
    # Wait beyond the 12 s Live-result timeout before starting another
    # identical phrase, so a late result from the canceled session cannot be
    # mistaken for the subsequent submit or hold test.
    time.sleep(13)
    if history_count() != baseline_history:
        raise RuntimeError("Canceled tap session unexpectedly added transcript history")
    if external_editor_text() != baseline_text:
        raise RuntimeError("Canceled tap session unexpectedly inserted editor text")
    print("PASS: real overlay tap-to-cancel left history and external editor unchanged")


def exercise_tap_submit(bubble_x, bubble_y, submit_center, pcm_duration_ms):
    baseline_history = history_count()
    baseline_text = external_editor_text()
    stage_synthetic_audio()
    tap_recording_bubble(bubble_x, bubble_y)
    if diagnostic_code("TEST_STATUS") != 11:
        raise RuntimeError("Tap-to-submit recording ended before the submit control was pressed")
    # Smaller 40 ms AudioRecord reads are genuinely paced on some emulators.
    # Do not submit before the synthetic phrase has finished playing.
    time.sleep(pcm_duration_ms / 1000 + 1)
    if diagnostic_code("TEST_STATUS") != 11:
        raise RuntimeError("Recording did not remain active for the synthetic phrase")
    capture_external_editor_screen("whisproid-recording-gesture.png")
    tap(*submit_center)
    # Capture immediately: Gemini can close an already-finalized turn faster
    # than a diagnostic broadcast round trip.
    capture_external_editor_screen("whisproid-transcribing-gesture.png")
    time.sleep(0.15)
    capture_external_editor_screen("whisproid-transcribing-settled-gesture.png")
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        if history_count() > baseline_history:
            break
        time.sleep(1)
    if history_count() <= baseline_history:
        raise RuntimeError("Tap-to-submit did not add a transcript to history")
    if not wait_for_status(10, timeout=10):
        raise RuntimeError("Tap-to-submit transcription did not return dictation to ready")
    current_text = external_editor_text()
    if PHRASE not in current_text.lower() or current_text == baseline_text:
        print(f"Safe tap-submit diagnostics: status={diagnostic_code('TEST_STATUS')}, "
              f"history_has_phrase={history_contains_phrase()}, "
              f"editor_chars={len(current_text)}, baseline_chars={len(baseline_text)}, "
              f"cancel_code={diagnostic_code('TEST_CANCEL_CODE')}")
        raise RuntimeError("Tap-to-submit transcript was not inserted into the external editor")
    print("PASS: real overlay tap-to-submit transcribed synthetic speech and inserted it")


def save_safe_screen(name):
    root = ui_tree()
    # Never capture the app's password field in Settings.
    if any(node.get("package") == APP and node.get("class") == "android.widget.EditText"
           for node in root.iter("node")):
        return
    screenshot = os.path.join(os.environ.get("RUNNER_TEMP", "/tmp"), name)
    with open(screenshot, "wb") as image:
        image.write(subprocess.run(["adb", "exec-out", "screencap", "-p"],
                                   check=True, capture_output=True, timeout=20).stdout)


def capture_external_editor_screen(name):
    # During an active session, UiAutomator hierarchy reads can disconnect
    # the accessibility service. These calls occur only after the external
    # editor was verified focused, never in the app's credential settings.
    screenshot = os.path.join(os.environ.get("RUNNER_TEMP", "/tmp"), name)
    with open(screenshot, "wb") as image:
        image.write(subprocess.run(["adb", "exec-out", "screencap", "-p"],
                                   check=True, capture_output=True, timeout=20).stdout)


def report_safe_app_crash(api_key):
    crash = adb("shell", "logcat", "-d", "-b", "crash", "-v", "brief")
    lines = crash.replace(api_key, "[REDACTED]").splitlines()
    for index, line in enumerate(lines):
        if f"Process: {APP}" in line:
            print("App crash log (redacted): " + "\n".join(lines[max(0, index - 2):index + 35])[:4500])



def run():
    api_key = os.environ.get("GOOGLE_AI_STUDIO_KEY", "")
    if not api_key:
        raise RuntimeError("GOOGLE_AI_STUDIO_KEY is not configured")
    install_and_prepare_app(api_key)
    width, height, density = display_size_and_density()
    bubble_size = int(58 * density)
    edge_margin = int(22 * density)
    bubble_x = width - edge_margin - bubble_size // 2
    bubble_y = int(105 * density) + bubble_size // 2
    cancel_center, submit_center = overlay_control_centers(width, density)
    pcm_duration_ms = os.path.getsize("app/src/debug/assets/synthetic.pcm") // 32
    hold_duration_ms = max(8000, pcm_duration_ms + 1800)
    before = diagnostic_code("TEST_STATUS")
    before_pid = adb("shell", "pidof", APP).strip()
    if not focused_external_editor():
        raise RuntimeError("Refusing gesture: external EditText is not focused")
    save_safe_screen("whisproid-before-gesture.png")
    if before in (2, 3):
        raise RuntimeError(f"Dictation prerequisites unavailable (status {before})")

    exercise_tap_cancel(bubble_x, bubble_y, cancel_center)
    exercise_tap_submit(bubble_x, bubble_y, submit_center, pcm_duration_ms)

    hold_baseline_history = history_count()
    hold_baseline_text = external_editor_text()

    # Stage anew for the hold/release session: the debug harness is consumed
    # once per capture session.
    stage_synthetic_audio()

    # The debug receiver arms the in-app substitution harness; this remains a
    # real accessibility-service hold/release gesture through AudioRecord.
    # Keep the swipe alive while collecting diagnostics from the held state.
    # Never include subprocess details in errors: the API key is not part of
    # this command, but keeping diagnostics generic makes the log boundary clear.
    swipe = subprocess.Popen(
        ["adb", "shell", "input", "swipe", str(bubble_x), str(bubble_y),
         str(bubble_x), str(bubble_y), str(hold_duration_ms)],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    try:
        time.sleep(1)
        during_status = diagnostic_code("TEST_STATUS")
        print(f"Safe during-hold diagnostics: status={during_status}")
        if during_status == 11:
            capture_external_editor_screen("whisproid-held-gesture.png")
        try:
            _, _ = swipe.communicate(timeout=hold_duration_ms / 1000 + 10)
        except subprocess.TimeoutExpired:
            swipe.kill()
            swipe.communicate()
            raise RuntimeError("Gesture subprocess timed out") from None
        if swipe.returncode:
            raise RuntimeError(f"Gesture subprocess failed (exit {swipe.returncode})")
    finally:
        if swipe.poll() is None:
            swipe.kill()
            swipe.communicate()
    print(f"Safe after-gesture diagnostics: status={diagnostic_code('TEST_STATUS')}, "
          f"cancel_code={diagnostic_code('TEST_CANCEL_CODE')}")

    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        # UiAutomator acquires a competing accessibility automation connection.
        # Do not dump the hierarchy until the app has completed transcription;
        # doing so mid-session can interrupt its accessibility service.
        if history_count() > hold_baseline_history:
            current_text = external_editor_text()
            if len(current_text) <= len(hold_baseline_text) or PHRASE not in current_text.lower():
                raise RuntimeError("Transcript reached history but not the external editor")
            save_safe_screen("whisproid-inserted-gesture.png")
            if not history_contains_phrase():
                raise RuntimeError("Transcript was inserted but not visible in local history")
            print("PASS: synthetic speech transcribed through the app microphone path and inserted into the external editor")
            return
        time.sleep(1.5)
    after_pid = adb("shell", "pidof", APP).strip()
    print(f"Safe debug status: bubble={diagnostic_code('TEST_STATUS')}, "
          f"history={diagnostic_code('TEST_HISTORY_COUNT')}, "
          f"process_changed={before_pid != after_pid}, "
          f"cancel_code={diagnostic_code('TEST_CANCEL_CODE')}")
    report_safe_app_crash(api_key)
    save_safe_screen("whisproid-after-gesture.png")
    raise RuntimeError("Expected synthetic transcript was not inserted into the external editor")


if __name__ == "__main__":
    if len(sys.argv) != 1:
        raise SystemExit("Usage: emulator_gemini_audio_e2e.py")
    run()

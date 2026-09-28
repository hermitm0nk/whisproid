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


def editor_visible_with_phrase():
    root = ui_tree()
    return any(node.get("package") == TEST_APP
               and node.get("class") == "android.widget.EditText"
               and PHRASE in (node.get("text") or "").lower()
               for node in root.iter("node"))


def history_contains_phrase():
    adb("shell", "am", "start", "-W", "-n", f"{APP}/{APP}.ui.MainActivity")
    root = ui_tree()
    history = next((node for node in root.iter("node")
                    if (node.get("text") or "").startswith("Transcript history (")), None)
    if history is None:
        raise RuntimeError("Transcript history control was not visible")
    tap(*bounds_center(history))
    return any(PHRASE in (node.get("text") or "").lower()
               for node in ui_tree().iter("node")
               if node.get("package") == APP)


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
    time.sleep(1)
    tap_text("API key and bubble appearance")

    root = ui_tree()
    field = next((item for item in root.iter("node")
                  if item.get("class") == "android.widget.EditText"), None)
    save = next((item for item in root.iter("node")
                 if item.get("text") == "Save API key"), None)
    if field is None or save is None:
        raise RuntimeError("Could not find the API key field and Save button")
    save_x, save_y = bounds_center(save)
    tap(*bounds_center(field))
    # The key is entered only into the password field and is never printed, dumped
    # to logs or captured in a screenshot/artifact.
    adb("shell", "input", "text", api_key)
    adb("shell", "input", "keyevent", "4")
    time.sleep(0.3)
    tap(save_x, save_y)
    time.sleep(0.5)

    # The Settings panel is an in-activity view. Reopening the launcher intent
    # can reuse that same screen, so restart the process to get a fresh Home.
    adb("shell", "am", "force-stop", APP)
    adb("shell", "am", "start", "-W", "-n", f"{APP}/{APP}.ui.MainActivity", timeout=30)
    time.sleep(0.5)
    if not any(node.get("text") == "Ready to dictate" for node in ui_tree().iter("node")):
        raise RuntimeError("Encrypted API key was not saved before the app restart")
    # force-stop tears down the enabled accessibility service. Toggle the
    # global state so Android binds it again after the app is relaunched.
    adb("shell", "settings", "put", "secure", "accessibility_enabled", "0")
    adb("shell", "settings", "put", "secure", "enabled_accessibility_services",
        f"{APP}/{APP}.core.BubbleAccessibilityService")
    adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
    # Start the microphone foreground service from this visible activity.
    tap_text("Enable dictation")
    time.sleep(1)
    adb("shell", "am", "start", "-W", "-n",
        f"{TEST_APP}/dev.hermitm0nk.flowbubble.HostActivity", "--ez", "focus", "true", timeout=30)
    time.sleep(3)
    if diagnostic_code("TEST_STATUS") == 2:
        raise RuntimeError("Accessibility service did not rebind after app restart")


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


def run():
    api_key = os.environ.get("GOOGLE_AI_STUDIO_KEY", "")
    if not api_key:
        raise RuntimeError("GOOGLE_AI_STUDIO_KEY is not configured")
    install_and_prepare_app(api_key)
    stage_synthetic_audio()

    width, height, density = display_size_and_density()
    bubble_size = int(58 * density)
    edge_margin = int(22 * density)
    bubble_x = width - edge_margin - bubble_size // 2
    bubble_y = int(105 * density) + bubble_size // 2
    pcm_duration_ms = os.path.getsize("app/src/debug/assets/synthetic.pcm") // 32
    hold_duration_ms = max(4000, pcm_duration_ms + 1800)
    before = diagnostic_code("TEST_STATUS")
    save_safe_screen("whisproid-before-gesture.png")
    if before in (2, 3):
        raise RuntimeError(f"Dictation prerequisites unavailable (status {before})")

    # The debug receiver arms the in-app substitution harness; this remains a
    # real accessibility-service hold/release gesture through AudioRecord.
    adb("shell", "input", "swipe", str(bubble_x), str(bubble_y),
        str(bubble_x), str(bubble_y), str(hold_duration_ms),
        timeout=hold_duration_ms / 1000 + 10)

    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        if editor_visible_with_phrase():
            if not history_contains_phrase():
                raise RuntimeError("Transcript was inserted but not visible in local history")
            print("PASS: synthetic speech transcribed through the app microphone path and inserted into the external editor")
            return
        time.sleep(1.5)
    print(f"Safe debug status: bubble={diagnostic_code('TEST_STATUS')}, "
          f"history={diagnostic_code('TEST_HISTORY_COUNT')}")
    save_safe_screen("whisproid-after-gesture.png")
    raise RuntimeError("Expected synthetic transcript was not inserted into the external editor")


if __name__ == "__main__":
    if len(sys.argv) != 1:
        raise SystemExit("Usage: emulator_gemini_audio_e2e.py")
    run()

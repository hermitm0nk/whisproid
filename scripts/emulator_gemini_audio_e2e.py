"""Experimental full app audio path test on a gRPC-enabled Android emulator."""
import os
import re
import subprocess
import sys
import time
import wave
import xml.etree.ElementTree as ET
from pathlib import Path

import grpc
import emulator_controller_pb2 as emulator_pb
import emulator_controller_pb2_grpc as emulator_grpc


APP = "dev.hermitm0nk.flowbubble"
TEST_APP = f"{APP}.test"
PHRASE = "today is monday and the sky is blue"
GRPC_TARGET = "127.0.0.1:8554"


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

    # Bring a fresh, visible MainActivity to the foreground before starting the
    # microphone foreground service, satisfying Android 14+ while-in-use rules.
    adb("shell", "am", "start", "-W", "-n", f"{APP}/{APP}.ui.MainActivity", timeout=30)
    time.sleep(0.5)
    tap_text("Enable dictation")
    time.sleep(1)
    adb("shell", "am", "start", "-W", "-n",
        f"{TEST_APP}/dev.hermitm0nk.flowbubble.HostActivity", "--ez", "focus", "true", timeout=30)
    time.sleep(2)


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


def inject_wav(path):
    def packets():
        with wave.open(str(path), "rb") as source:
            if (source.getnchannels(), source.getsampwidth(), source.getframerate()) != (1, 2, 16000):
                raise RuntimeError("Synthetic test WAV must be mono, signed 16-bit, 16 kHz")
            audio_format = emulator_pb.AudioFormat(
                samplingRate=16000,
                channels=emulator_pb.AudioFormat.Mono,
                format=emulator_pb.AudioFormat.AUD_FMT_S16,
                mode=emulator_pb.AudioFormat.MODE_UNSPECIFIED,
            )
            frames_per_packet = 4800  # 300 ms; emulator gRPC input is back-pressured.
            while True:
                audio = source.readframes(frames_per_packet)
                if not audio:
                    break
                yield emulator_pb.AudioPacket(format=audio_format, audio=audio)

    with grpc.insecure_channel(GRPC_TARGET) as channel:
        grpc.channel_ready_future(channel).result(timeout=15)
        stub = emulator_grpc.EmulatorControllerStub(channel)
        stub.injectAudio(packets(), timeout=30)


def run(wav_path):
    api_key = os.environ.get("GOOGLE_AI_STUDIO_KEY", "")
    if not api_key:
        raise RuntimeError("GOOGLE_AI_STUDIO_KEY is not configured")
    if not wav_path.is_file():
        raise RuntimeError("Synthetic test WAV does not exist")
    install_and_prepare_app(api_key)

    width, height, density = display_size_and_density()
    bubble_size = int(58 * density)
    edge_margin = int(22 * density)
    bubble_x = width - edge_margin - bubble_size // 2
    bubble_y = int(105 * density) + bubble_size // 2

    # Hold past the app's 330 ms long-press threshold. Start injecting only after
    # AudioRecord has had time to open; release is the app's speech activityEnd.
    hold = subprocess.Popen(
        ["adb", "shell", "input", "swipe", str(bubble_x), str(bubble_y),
         str(bubble_x), str(bubble_y), "10000"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        time.sleep(1.0)
        inject_wav(wav_path)
        time.sleep(3.0)
    finally:
        try:
            hold.wait(timeout=12)
        except subprocess.TimeoutExpired:
            hold.kill()
            raise RuntimeError("Emulator hold gesture did not finish")

    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        if editor_visible_with_phrase():
            if not history_contains_phrase():
                raise RuntimeError("Transcript was inserted but not visible in local history")
            print("PASS: synthetic speech transcribed through the app microphone path and inserted into the external editor")
            return
        time.sleep(1.5)
    raise RuntimeError("Expected synthetic transcript was not inserted into the external editor")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: emulator_gemini_audio_e2e.py <synthetic-wav>")
    run(Path(sys.argv[1]))

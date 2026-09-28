#!/usr/bin/env bash
set -u
api_level="$1"
adb shell input keyevent 82
adb shell wm dismiss-keyguard
test_status=0
./gradlew connectedDebugAndroidTest --no-daemon || test_status=$?
mkdir -p screenshots
adb pull /sdcard/Pictures/Whisproid "screenshots/api-$api_level" || true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell settings put secure enabled_accessibility_services dev.hermitm0nk.flowbubble/dev.hermitm0nk.flowbubble.core.BubbleAccessibilityService
adb shell settings put secure accessibility_enabled 1
sleep 3
adb shell dumpsys accessibility > "screenshots/accessibility-api-$api_level.txt"
adb shell am start -n dev.hermitm0nk.flowbubble.test/dev.hermitm0nk.flowbubble.HostActivity --ez focus false
editor_coordinates=""
quickstep_recovered=0
for attempt in {1..12}; do
    adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
    adb shell cat /sdcard/window.xml > "screenshots/unfocused-api-$api_level.xml"
    detected_target=$(python3 - "screenshots/unfocused-api-$api_level.xml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

def center(node):
    bounds = list(map(int, re.findall(r"\d+", node.attrib["bounds"])))
    if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
        return f"{(bounds[0] + bounds[2]) // 2} {(bounds[1] + bounds[3]) // 2}"
    return None

try:
    root = ET.parse(sys.argv[1]).getroot()
    nodes = list(root.iter("node"))
    texts = {n.get("text") for n in nodes}
    if "Quickstep isn't responding" in texts and "Close app" in texts:
        close_app = next(n for n in nodes if n.get("text") == "Close app")
        coords = center(close_app)
        if coords:
            print(f"ANR {coords}")
    else:
        editor = next(n for n in nodes if n.get("text") == "Write a message")
        coords = center(editor)
        if coords:
            print(f"EDITOR {coords}")
except (ET.ParseError, OSError, KeyError, StopIteration, ValueError):
    pass
PY
)
    if [[ "$detected_target" =~ ^ANR[[:space:]]+[0-9]+[[:space:]]+[0-9]+$ ]]; then
        if (( quickstep_recovered == 0 )); then
            echo "Detected Quickstep ANR dialog; tapping Close app and retrying HostActivity" >&2
            adb shell input tap ${detected_target#ANR }
            quickstep_recovered=1
            sleep 2
            adb shell am start -n dev.hermitm0nk.flowbubble.test/dev.hermitm0nk.flowbubble.HostActivity --ez focus false >/dev/null 2>&1 || true
        fi
    elif [[ "$detected_target" =~ ^EDITOR[[:space:]]+[0-9]+[[:space:]]+[0-9]+$ ]]; then
        editor_coordinates=${detected_target#EDITOR }
        break
    fi
    if (( attempt == 6 )); then
        adb shell am start -n dev.hermitm0nk.flowbubble.test/dev.hermitm0nk.flowbubble.HostActivity --ez focus false >/dev/null 2>&1 || true
    fi
    sleep 1
done
if [[ ! "$editor_coordinates" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]]; then
    echo "Failed to find a usable 'Write a message' editor in HostActivity after 12 UI dumps" >&2
    exit 1
fi
adb exec-out screencap -p > "screenshots/unfocused-api-$api_level.png"
adb shell input tap $editor_coordinates
sleep 3
adb shell uiautomator dump /sdcard/window.xml >/dev/null
adb shell cat /sdcard/window.xml > "screenshots/focused-api-$api_level.xml"
adb exec-out screencap -p > "screenshots/focused-api-$api_level.png"
python3 scripts/check_overlay_screenshots.py \
    "screenshots/unfocused-api-$api_level.png" \
    "screenshots/focused-api-$api_level.png" \
    "screenshots/unfocused-api-$api_level.xml" \
    "screenshots/focused-api-$api_level.xml" || test_status=1
adb shell am broadcast -a dev.hermitm0nk.flowbubble.TEST_INSERT \
    -n dev.hermitm0nk.flowbubble/dev.hermitm0nk.flowbubble.core.TestBridgeReceiver \
    > "screenshots/insertion-test-api-$api_level.txt" || test_status=1
sleep 2
adb shell uiautomator dump /sdcard/window.xml >/dev/null
adb shell cat /sdcard/window.xml > "screenshots/inserted-api-$api_level.xml"
adb exec-out screencap -p > "screenshots/inserted-api-$api_level.png"
adb shell am broadcast -a dev.hermitm0nk.flowbubble.TEST_HISTORY \
    -n dev.hermitm0nk.flowbubble/dev.hermitm0nk.flowbubble.core.TestBridgeReceiver \
    > "screenshots/history-test-api-$api_level.txt" || test_status=1
if ! grep -q 'result=1' "screenshots/insertion-test-api-$api_level.txt" || \
    ! python3 - "screenshots/inserted-api-$api_level.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
editors = [node for node in root.iter("node") if node.get("class") == "android.widget.EditText"
           and node.get("package") == "dev.hermitm0nk.flowbubble.test"]
assert len(editors) == 1, f"Expected one external editor; found {len(editors)}"
assert editors[0].get("text") == "Inserted from accessibility test", editors[0].get("text")
PY
then
    echo 'Cross-app editor text was not exactly the transcript'
    test_status=1
fi
if ! grep -q 'result=1' "screenshots/history-test-api-$api_level.txt"; then
    echo 'Cross-app transcript insertion and history check failed'
    cat "screenshots/insertion-test-api-$api_level.txt" "screenshots/history-test-api-$api_level.txt"
    test_status=1
fi
adb logcat -d -t 500 -v brief > "screenshots/logcat-api-$api_level.txt" || true
exit "$test_status"

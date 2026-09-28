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
sleep 3
adb shell uiautomator dump /sdcard/window.xml >/dev/null
adb shell cat /sdcard/window.xml > "screenshots/unfocused-api-$api_level.xml"
adb exec-out screencap -p > "screenshots/unfocused-api-$api_level.png"
editor_coordinates=$(python3 - "screenshots/unfocused-api-$api_level.xml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
editor = next(n for n in root.iter("node") if n.get("text") == "Write a message")
x1, y1, x2, y2 = map(int, re.findall(r"\d+", editor.attrib["bounds"]))
print((x1 + x2) // 2, (y1 + y2) // 2)
PY
)
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

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
adb shell am start -n dev.hermitm0nk.flowbubble.test/dev.hermitm0nk.flowbubble.HostActivity --ez focus false
sleep 3
adb shell uiautomator dump /sdcard/window.xml >/dev/null
adb shell cat /sdcard/window.xml > "screenshots/unfocused-api-$api_level.xml"
adb exec-out screencap -p > "screenshots/unfocused-api-$api_level.png"
if grep -q 'Hold to dictate' "screenshots/unfocused-api-$api_level.xml"; then
    echo 'Overlay shown without an active text field'
    test_status=1
fi
adb shell am force-stop dev.hermitm0nk.flowbubble.test
adb shell am start -n dev.hermitm0nk.flowbubble.test/dev.hermitm0nk.flowbubble.HostActivity --ez focus true
sleep 3
adb shell uiautomator dump /sdcard/window.xml >/dev/null
adb shell cat /sdcard/window.xml > "screenshots/focused-api-$api_level.xml"
adb exec-out screencap -p > "screenshots/focused-api-$api_level.png"
if ! grep -q 'Hold to dictate' "screenshots/focused-api-$api_level.xml"; then
    echo 'Overlay absent from a focused external editor'
    test_status=1
fi
exit "$test_status"

#!/usr/bin/env bash
# PodSteroid APK deployer — builds nothing, just pushes + installs the debug
# APK over adb and launches the app. Shows a transfer progress bar via push.
#
# The target device is auto-detected from `adb devices` (prefers a Wi-Fi /
# network device, i.e. one with a ":" in its serial) so a changing Wi-Fi
# IP/port doesn't break the script. If no device is connected yet, it waits
# (handles the connection flipping during a re-pair).
set -u

cd /opt/kubernetes/podsteroidv2
export PATH=/opt/nas/android-sdk/platform-tools:$PATH
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.tiquasar.podsteroid.debug
ACT=com.tiquasar.podsteroid.debug/.MainActivity

if [ ! -f "$APK" ]; then
    echo "APK not found at $APK — run ./gradlew :app:assembleDebug first."
    exit 1
fi

# Resolve a connected device, preferring a network (Wi-Fi) one.
detect_serial() {
    # Prefer a network device (serial contains ":"), then any online device.
    local net any
    net=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" && $1 ~ /:/ {print $1; exit}')
    any=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}')
    echo "${net:-$any}"
}

SERIAL=""
for i in $(seq 1 15); do
    SERIAL=$(detect_serial)
    if [ -n "$SERIAL" ]; then
        echo "Using device: $SERIAL"
        break
    fi
    echo "Waiting for a device to appear ($(($i*2))s)..."
    sleep 2
done

if [ -z "$SERIAL" ]; then
    echo "No adb device found. Connect via USB or Wi-Fi (adb connect <ip>:<port>) and retry."
    exit 1
fi

echo "=== pushing APK (progress bar) ==="
adb -s "$SERIAL" push "$APK" /data/local/tmp/app-debug.apk

echo "=== installing ==="
adb -s "$SERIAL" shell pm install -r -t /data/local/tmp/app-debug.apk

echo "=== cleanup ==="
adb -s "$SERIAL" shell rm -f /data/local/tmp/app-debug.apk

echo "=== launching $PKG ==="
adb -s "$SERIAL" shell am start -n "$ACT"

echo "DONE"

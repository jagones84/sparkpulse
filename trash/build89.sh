#!/bin/bash
# JAG-89: build + unit tests + install v1.6.30 on oneplus-15r.
set -e
cd /home/jagones/Repositories/sparkpulse-app
./gradlew testDebugUnitTest assembleDebug -q 2>&1 | tail -n 5
/usr/bin/adb -s oneplus-15r:5555 install -r app/build/outputs/apk/debug/app-debug.apk
/usr/bin/adb -s oneplus-15r:5555 shell dumpsys package com.jagones.sparkpulse | grep -E "versionName|versionCode" | head -n 2

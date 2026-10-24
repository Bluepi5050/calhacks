#!/bin/bash
# Build the SmartBag customer APK. Usage: tools/build.sh  (reads the API URL from ../api/endpoint.json)
set -e
cd /workspace/smartbag/android
export ANDROID_HOME=/tmp/android-sdk GRADLE_USER_HOME=/tmp/gradle-home
echo "sdk.dir=$ANDROID_HOME" > local.properties
[ -f gradlew ] || /tmp/gradle-8.7/bin/gradle wrapper --gradle-version 8.7 -q
./gradlew --no-daemon -q assembleRelease "$@"
ls -la app/build/outputs/apk/release/

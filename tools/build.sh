#!/bin/bash
# Build the SmartBag customer APK (release, debug-signed). Usage: tools/build.sh [-PstdbHost=... -PstdbDb=...]
set -e
cd "$(dirname "$0")/../android"
export ANDROID_HOME=/tmp/android-sdk GRADLE_USER_HOME=/tmp/gradle-home
echo "sdk.dir=$ANDROID_HOME" > local.properties
[ -f gradlew ] || /tmp/gradle-8.7/bin/gradle wrapper --gradle-version 8.7 -q
./gradlew --no-daemon -q assembleRelease "$@"
ls -la app/build/outputs/apk/release/

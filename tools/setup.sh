
#!/bin/bash
# SmartBag Android toolchain (idempotent). SDK + Gradle caches live in /tmp (big disk); rerun after a container reset.
set -e
export ANDROID_HOME=/tmp/android-sdk
if ! command -v javac >/dev/null 2>&1; then
  sudo apt-get update -qq && sudo apt-get install -y -qq openjdk-17-jdk-headless unzip wget >/dev/null
fi
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p $ANDROID_HOME/cmdline-tools && cd /tmp
  wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O clt.zip
  unzip -q -o clt.zip -d $ANDROID_HOME/cmdline-tools && rm -rf $ANDROID_HOME/cmdline-tools/latest
  mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
fi
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null 2>&1 || true
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null
if [ ! -x /tmp/gradle-8.7/bin/gradle ]; then
  cd /tmp && wget -q https://services.gradle.org/distributions/gradle-8.7-bin.zip -O gradle.zip && unzip -q -o gradle.zip && rm gradle.zip
fi
echo TOOLCHAIN_OK

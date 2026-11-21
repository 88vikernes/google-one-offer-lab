#!/usr/bin/env bash
# build-dex.sh — compile Play Billing 5.2.1 + src/Probe.java into one classes.dex
#
# Inputs needed in the working dir (or via env vars):
#   ANDROID_JAR   android.jar (API 34)      e.g. android-34/android.jar from platform-34-ext*.zip
#   R8_JAR        r8 jar (contains d8)      e.g. r8-8.3.37.jar from Google Maven
#   JDK_BIN       directory with javac/java (JDK 11+; JDK 17 recommended)
#
# Usage:
#   ANDROID_JAR=/path/android.jar R8_JAR=/path/r8.jar JDK_BIN=/path/jdk/bin ./scripts/build-dex.sh
#
# Output: out/classes.dex  (push to /data/local/tmp/probe.dex, chmod 444)

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
cd "$ROOT"

BILLING_VERSION="5.2.1"
AAR="billing-${BILLING_VERSION}.aar"
BILLING_JAR="classes.jar"

: "${ANDROID_JAR:?set ANDROID_JAR to android.jar (API 34)}"
: "${R8_JAR:?set R8_JAR to r8-*.jar}"
: "${JDK_BIN:?set JDK_BIN to the bin/ directory of a JDK}"

if [ ! -f "$AAR" ]; then
  echo "==> downloading $AAR"
  curl -sLO "https://dl.google.com/dl/android/maven2/com/android/billingclient/billing/${BILLING_VERSION}/${AAR}"
fi

if [ ! -f "$BILLING_JAR" ]; then
  echo "==> extracting $BILLING_JAR from $AAR"
  unzip -o "$AAR" "$BILLING_JAR" -d . >/dev/null
fi

rm -rf classes out
mkdir -p classes out

echo "==> javac"
"$JDK_BIN/javac" -source 8 -target 8 -nowarn \
  -cp "$ANDROID_JAR:$BILLING_JAR" \
  -d classes src/Probe.java

echo "==> d8"
"$JDK_BIN/java" -cp "$R8_JAR" com.android.tools.r8.D8 \
  --min-api 29 --lib "$ANDROID_JAR" \
  --output out "$BILLING_JAR" classes/com/eni/*.class

ls -l out/classes.dex
cat <<'EOT'

Next:
  adb -s <serial> push out/classes.dex /data/local/tmp/probe.dex
  adb -s <serial> shell "su -c 'chmod 444 /data/local/tmp/probe.dex'"
  adb -s <serial> shell "am start -n com.google.android.apps.subscriptions.red/.LauncherActivity"
  PID=$(adb -s <serial> shell "pidof com.google.android.apps.subscriptions.red" | tr -d '\r')
  frida -H 127.0.0.1:27042 -p $PID -l scripts/probe_run.js
  adb -s <serial> logcat -s ENIPROBE
EOT

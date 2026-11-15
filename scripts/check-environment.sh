#!/bin/sh
# Read-only prerequisite check. Do not download large archives if tools are missing.
set -eu
if [ -n "${JAVA_HOME:-}" ]; then
    java_cmd="$JAVA_HOME/bin/java"
    javac_cmd="$JAVA_HOME/bin/javac"
else
    java_cmd=java
    javac_cmd=javac
fi
java_info=$("$java_cmd" -version 2>&1) || {
    echo 'ERROR: JDK 17+ is required. Install a JDK and set JAVA_HOME.' >&2
    exit 1
}
java_major=$(printf '%s\n' "$java_info" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)
case "$java_major" in
    ''|*[!0-9]*) echo 'ERROR: Cannot determine Java version. Set JAVA_HOME to JDK 17+.' >&2; exit 1 ;;
esac
[ "$java_major" -ge 17 ] || { echo 'ERROR: JDK 17+ is required.' >&2; exit 1; }
"$javac_cmd" -version >/dev/null 2>&1 || { echo 'ERROR: javac is missing; a full JDK is required.' >&2; exit 1; }
project_dir=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk_dir" ] && [ -f "$project_dir/local.properties" ]; then
    sdk_dir=$(sed -n 's/^sdk\.dir=//p' "$project_dir/local.properties" | head -n 1)
fi
[ -n "$sdk_dir" ] || { echo 'ERROR: Set ANDROID_HOME or sdk.dir in local.properties (Android SDK 35).' >&2; exit 1; }
[ -f "$sdk_dir/platforms/android-35/android.jar" ] || { echo 'ERROR: Install Android SDK Platform 35.' >&2; exit 1; }
[ -d "$sdk_dir/build-tools/35.0.0" ] || { echo 'ERROR: Install Android SDK Build Tools 35.0.0.' >&2; exit 1; }
printf '%s\n' "Environment ready: JDK $java_major, Android SDK 35."

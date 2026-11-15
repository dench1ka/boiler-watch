#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./scripts/check-environment.sh
# Keep Gradle state inside the project in restricted environments.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.gradle-user-home}"
if command -v gradle >/dev/null 2>&1; then
    exec gradle check lintRelease assembleDebug assembleRelease assembleDebugAndroidTest "$@"
fi
# Bootstrap a pinned Gradle distribution in the project; no global installation.
version=8.11.1
cache="$PWD/.tools"
mkdir -p "$cache"
if [ ! -x "$cache/gradle-$version/bin/gradle" ]; then
    curl --fail --location --retry 2 "https://services.gradle.org/distributions/gradle-$version-bin.zip" -o "$cache/gradle.zip"
    checksum=$(curl --fail --location "https://services.gradle.org/distributions/gradle-$version-bin.zip.sha256")
    actual=$(shasum -a 256 "$cache/gradle.zip" | cut -d ' ' -f 1)
    [ "$actual" = "$checksum" ] || { echo 'Gradle checksum mismatch' >&2; exit 1; }
    unzip -q -o "$cache/gradle.zip" -d "$cache"
    rm "$cache/gradle.zip"
fi
exec "$cache/gradle-$version/bin/gradle" check lintRelease assembleDebug assembleRelease assembleDebugAndroidTest "$@"

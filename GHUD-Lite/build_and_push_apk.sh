#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

if [[ -d /root/.local/share/mise/installs/java/17.0.2 ]]; then
  export JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2
  export PATH="$JAVA_HOME/bin:$PATH"
fi

./gradlew assembleDebug --no-daemon

APK_PATH="$(find app/build/outputs/apk -type f -name "*debug*.apk" | head -n 1)"
if [[ -z "${APK_PATH}" ]]; then
  echo "Debug APK not found"
  exit 1
fi

mkdir -p releases
cp "$APK_PATH" releases/ghud-lite-debug.apk

git add releases/ghud-lite-debug.apk
git commit -m "Add GHUD-Lite debug APK artifact" || true

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
git push -u origin "$CURRENT_BRANCH"

echo "Done: releases/ghud-lite-debug.apk"

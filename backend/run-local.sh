#!/usr/bin/env bash
# Runs the backend on this Mac and links a USB-connected phone to it.
# Usage: ./run-local.sh   (stop with Ctrl+C). Secrets come from backend/.env.
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb

# The phone reaches the Mac's port 8080 over the cable ("localhost:8080" inside the app).
if "$ADB" get-state >/dev/null 2>&1; then
  "$ADB" reverse tcp:8080 tcp:8080 >/dev/null && echo "Phone linked over USB."
else
  echo "No phone over USB (plug it in with USB debugging on, then run: $ADB reverse tcp:8080 tcp:8080)"
fi

if curl -s --noproxy '*' -m 2 http://localhost:8080/actuator/health | grep -q UP; then
  echo "The backend is already running on port 8080. Nothing else to start."
  exit 0
fi

echo "Starting the backend (ready when you see 'Started DailyMoneyApplication')..."
# caffeinate keeps the Mac awake while the server runs.
exec caffeinate -i ./gradlew bootRun

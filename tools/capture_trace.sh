#!/usr/bin/env bash
# Capture the app's full-rate engine trace from the phone into a CSV on THIS computer.
# The app streams rows to logcat tagged "IDR-CSV,"; this filters + strips the prefix.
#
# Usage:  tools/capture_trace.sh [output.csv]
#   1. connect the phone (adb devices shows it)
#   2. run this, then start / use the app
#   3. Ctrl-C when done — the CSV is on your computer
set -euo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
OUT="${1:-idr_trace_$(date +%Y%m%d_%H%M%S).csv}"

command -v "$ADB" >/dev/null 2>&1 || { echo "adb not found at $ADB (set ADB=/path/to/adb)"; exit 1; }
"$ADB" get-state >/dev/null 2>&1 || { echo "no device — check 'adb devices'"; exit 1; }

echo "Capturing engine trace -> $OUT   (Ctrl-C to stop)"
"$ADB" logcat -c                      # clear the old buffer so the file starts fresh
"$ADB" logcat -s System.out:I \
  | grep --line-buffered "IDR-CSV," \
  | sed -u 's/.*IDR-CSV,//' \
  > "$OUT"

#!/usr/bin/env bash
# Change the voice (or any setting) of the Kokoro TTS engine on a connected phone.
#
#   tools/set-voice.sh bm_george
#   tools/set-voice.sh --list                 # ask the server what it has
#   tools/set-voice.sh af_heart --speed 1.2
#
# Why this exists: the engine stores its configuration in SharedPreferences, and
# until the settings screen ships this is the only way to change it. Writing the
# file directly is safe because the app is debuggable, but the running process
# caches SharedPreferences in memory, so the app is force-stopped afterwards to
# make it re-read.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
# Prefer an adb on PATH, then $ANDROID_HOME/$ANDROID_SDK_ROOT, then the local
# .tooling checkout, so the scripts work on a plain clone.
ADB="${ADB:-$(command -v adb || true)}"
if [ -z "$ADB" ]; then
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$REPO/.tooling/android-sdk"; do
    [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ] && ADB="$sdk/platform-tools/adb" && break
  done
fi
[ -n "$ADB" ] || { echo "no adb found; set ANDROID_HOME or put adb on PATH" >&2; exit 1; }
PKG=dev.kokoro.tts

VOICE=""
SPEED=""
ENDPOINT=""
LIST=0
while [ $# -gt 0 ]; do
  case "$1" in
    --list) LIST=1; shift ;;
    --speed) SPEED="$2"; shift 2 ;;
    --endpoint) ENDPOINT="$2"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) VOICE="$1"; shift ;;
  esac
done

SERIAL="${ANDROID_SERIAL:-}"
if [ -z "$SERIAL" ]; then
  # Field 2 is the connection state; matching on it avoids a non-portable '\t'
  # in awk (mawk does not expand it, so the old pattern silently matched nothing).
  # Prefer a physical device over the emulator, then fall back to any device.
  devices="$("$ADB" devices | awk '$2 == "device" {print $1}')" || true
  SERIAL="$(printf '%s\n' "$devices" | grep -v '^emulator-' | head -1)" || true
  [ -n "$SERIAL" ] || SERIAL="$(printf '%s\n' "$devices" | head -1)" || true
fi
[ -n "$SERIAL" ] || { echo "no device attached; run 'adb devices'" >&2; exit 1; }
A=("$ADB" -s "$SERIAL")

# `|| true`: right after a fresh install there is no prefs file yet, so `run-as`
# exits non-zero -- and under `set -o pipefail` that aborted the script silently.
current_endpoint="$("${A[@]}" shell "run-as $PKG grep -o 'http://[^<]*' shared_prefs/kokoro.xml" 2>/dev/null | tr -d '\r' | head -1 || true)"
current_endpoint="${current_endpoint:-http://localhost:8880/v1/audio/speech}"

if [ "$LIST" = 1 ]; then
  base="${current_endpoint%/v1/audio/speech}"
  echo "voices offered by $base:"
  curl -sS --max-time 15 "$base/v1/audio/voices" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); v=d.get("voices",d); [print("  "+(x if isinstance(x,str) else x.get("id",""))) for x in v]' \
    | sort
  exit 0
fi

[ -n "$VOICE" ] || { echo "usage: $0 <voice> [--speed N] [--endpoint URL]   |   $0 --list" >&2; exit 2; }

# These greps legitimately find nothing (e.g. no speed pref has ever been
# written), which would exit 1 and, under `set -e` + pipefail, kill the script
# silently. Tolerate the miss and fall back to the engine's own default.
cur_voice="$("${A[@]}" shell "run-as $PKG grep -o '<string name=\"voice\">[^<]*' shared_prefs/kokoro.xml" 2>/dev/null | sed 's/.*>//' | tr -d '\r' || true)"
cur_voice="${cur_voice:-af_heart}"
cur_speed="$("${A[@]}" shell "run-as $PKG grep -o 'value=\"[0-9]*\" name=\"speed\"' shared_prefs/kokoro.xml" 2>/dev/null | grep -o '[0-9]*' | head -1 | tr -d '\r' || true)"
cur_speed="${cur_speed:-100}"

speed="${SPEED:-$(python3 -c "print($cur_speed/100)")}"
endpoint="${ENDPOINT:-$current_endpoint}"

xml="/tmp/kokoro-$$.xml"
cat > "$xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="endpoint">$endpoint</string>
    <string name="voice">$VOICE</string>
    <string name="model">kokoro</string>
    <string name="format">pcm</string>
    <boolean name="strip_emoji" value="true" />
</map>
EOF

"${A[@]}" push "$xml" /data/local/tmp/kokoro.xml >/dev/null
"${A[@]}" shell "run-as $PKG sh -c 'cp /data/local/tmp/kokoro.xml shared_prefs/kokoro.xml'"
rm -f "$xml"

# The process caches SharedPreferences in memory.
"${A[@]}" shell am force-stop "$PKG"

echo "device      : $SERIAL"
echo "voice       : $cur_voice -> $VOICE"
echo "speed       : $speed"
echo "endpoint    : $endpoint"
echo
echo "Verify with:"
echo "  adb -s $SERIAL shell \"am start -n $PKG/.EngineTestActivity --es text 'Testing the new voice.' --es out /sdcard/Download/voice.wav\""
echo "  adb -s $SERIAL logcat -d | grep 'KokoroTts: synth ok'   # should show voice=$VOICE"

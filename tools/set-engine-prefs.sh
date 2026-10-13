#!/usr/bin/env bash
# Set engine preferences on a connected device.
#
#   tools/set-engine-prefs.sh add_gaps=false trim_silence=true
#   tools/set-engine-prefs.sh voice=bm_george mid_sentence_gap_ms=120
#   tools/set-engine-prefs.sh --show
#
# Edits the preferences XML in place, preserving every key you do not name.
# The running process caches SharedPreferences in memory, so the app is
# force-stopped afterwards to make it re-read.
#
# This exists because doing the same thing with nested sed over `adb shell`
# silently fails: the quoting survives bash, then adb, then sh -c, and does not
# survive the last hop, so the edit appears to apply and changes nothing.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
ADB="$REPO/.tooling/android-sdk/platform-tools/adb"
PKG=dev.kokoro.tts
PREFS=shared_prefs/kokoro.xml

SERIAL="${ANDROID_SERIAL:-}"
if [ -z "$SERIAL" ]; then
  devices="$("$ADB" devices | awk '$2 == "device" {print $1}')" || true
  SERIAL="$(printf '%s\n' "$devices" | grep -v '^emulator-' | head -1)" || true
  [ -n "$SERIAL" ] || SERIAL="$(printf '%s\n' "$devices" | head -1)" || true
fi
[ -n "$SERIAL" ] || { echo "no device attached" >&2; exit 1; }
A=("$ADB" -s "$SERIAL")

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
local_xml="$tmp/kokoro.xml"

"${A[@]}" shell "run-as $PKG cat $PREFS" > "$local_xml" 2>/dev/null || true

if [ "${1:---show}" = "--show" ]; then
  cat "$local_xml"
  exit 0
fi

python3 - "$local_xml" "$@" <<'PY'
import sys, xml.etree.ElementTree as ET, pathlib

path = pathlib.Path(sys.argv[1])
assignments = sys.argv[2:]

if path.exists() and path.stat().st_size > 0:
    tree = ET.parse(path)
    root = tree.getroot()
else:
    root = ET.Element("map")

def find(name):
    for child in root:
        if child.get("name") == name:
            return child
    return None

for item in assignments:
    if "=" not in item:
        raise SystemExit(f"expected key=value, got {item!r}")
    key, value = item.split("=", 1)
    if value.lower() in ("true", "false"):
        tag = "boolean"
    elif value.lstrip("-").isdigit():
        tag = "int"
    else:
        tag = "string"
    node = find(key)
    if node is None:
        node = ET.SubElement(root, tag, {"name": key})
    else:
        node.tag = tag
    if tag == "boolean":
        node.set("value", value.lower())
    elif tag == "int":
        node.set("value", value)
    else:
        node.text = value

path.write_text("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + ET.tostring(root, encoding="unicode") + "\n")
PY

"${A[@]}" push "$local_xml" /data/local/tmp/kokoro.xml >/dev/null
"${A[@]}" shell "run-as $PKG sh -c 'cp /data/local/tmp/kokoro.xml $PREFS'"
"${A[@]}" shell am force-stop "$PKG"

echo "device: $SERIAL"
echo "prefs now:"
"${A[@]}" shell "run-as $PKG cat $PREFS" | sed 's/^/  /'

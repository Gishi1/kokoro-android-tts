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

# An empty read means one of two very different things: the app is unreachable, or
# it simply has not written any preferences yet. Only the first is unsafe to write
# over, so ask the device which it is.
prefs_state="$("${A[@]}" shell "run-as $PKG sh -c 'test -f $PREFS && echo PRESENT || echo ABSENT'" 2>/dev/null | tr -d '\r' | head -1 || true)"

# Refuse to write unless the current file was read successfully. An earlier version
# carried on regardless, so a failed read produced a file containing ONLY the keys
# named on the command line -- silently dropping add_gaps, max_pause_ms and anything
# else, and reverting them to engine defaults. That is a genuine footgun: the user
# sees settings change on their own, with no error anywhere.
if [ "${1:---show}" != "--show" ]; then
  if [ ! -s "$local_xml" ]; then
    if [ "$prefs_state" = "ABSENT" ]; then
      # Fresh install: nothing has ever been saved, so there is no key to drop.
      # Start from the empty map Android itself would have written.
      cat > "$local_xml" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map />
XML
    else
      echo "refusing to write: could not read $PREFS from $SERIAL." >&2
      echo "Nothing has been changed. Check the device is connected and the app is installed." >&2
      exit 1
    fi
  fi
  if ! python3 -c "
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
sys.exit(0 if root is not None and root.tag == 'map' else 1)
" "$local_xml"; then
    echo "refusing to write: $PREFS did not parse as a preferences file." >&2
    echo "Nothing has been changed. Current contents:" >&2
    cat "$local_xml" >&2
    exit 1
  fi
fi

if [ "${1:---show}" = "--show" ]; then
  if [ -s "$local_xml" ]; then
    cat "$local_xml"
  else
    echo "(no $PREFS on $SERIAL yet - the engine is using its built-in defaults)" >&2
  fi
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

#!/usr/bin/env bash
# Compile the pure-Java core. No Android SDK and no Gradle are required: the
# core is deliberately free of Android imports so the splitter can be tested on
# a plain JVM before it is ever pushed to a device.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/build/classes"

JAVA_HOME="${JAVA_HOME:-/nix/store/5badkg3gmzg1c29akwglknkizfg6zj0g-openjdk-17.0.17+8/lib/openjdk}"
JAVAC="$JAVA_HOME/bin/javac"
if [ ! -x "$JAVAC" ]; then
  JAVAC="$(command -v javac)"
fi

rm -rf "$OUT"
mkdir -p "$OUT"

find "$HERE/src/main/java" -name '*.java' -printf '"%p"\n' > "$HERE/build/sources.txt"
"$JAVAC" -encoding UTF-8 -d "$OUT" @"$HERE/build/sources.txt"

echo "compiled $(wc -l < "$HERE/build/sources.txt") source files -> $OUT"

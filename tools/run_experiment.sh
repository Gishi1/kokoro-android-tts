#!/usr/bin/env bash
# Full measurement pipeline for the kokoro-splitter experiment.
#
#   fit (cached ground truth) -> generate Java cost model -> compile core
#   -> JVM self-test -> baseline (naive chars) vs prototype (Kokoro-aware)
#   -> probe the real Kokoro server for latency and over-long behaviour
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
PY="$REPO/.tooling/kokoro-venv/bin/python"
MODEL="$REPO/docs/research/token_model.json"
CORPORA=("$REPO/data/corpus_pride_prejudice.txt" "$REPO/data/corpus_mixed.txt")
JAVA_HOME="${JAVA_HOME:-/nix/store/5badkg3gmzg1c29akwglknkizfg6zj0g-openjdk-17.0.17+8/lib/openjdk}"

export UV_CACHE_DIR="$REPO/.tooling/uv-cache"
export HF_HOME="$REPO/.tooling/hf-cache"
export TMPDIR=/tmp
export JAVA_HOME

cd "$REPO"
FILTER='joblib|warn|repo_id|FutureWarning'

echo "=== [1/7] waiting for the token-model fit ==="
for _ in $(seq 1 180); do
  pgrep -f "fit_token_model.py" > /dev/null || break
  sleep 5
done
grep -viE "$FILTER" "$REPO/.tooling/fit2.log" | tail -30 || true
[ -f "$MODEL" ] || { echo "FATAL: no $MODEL"; exit 1; }

echo
echo "=== [2/7] generate KokoroCostModel.java from the fit ==="
python3 "$HERE/gen_cost_model.py" --model "$MODEL" \
    --out "$REPO/core/src/main/java/dev/kokoro/tts/KokoroCostModel.java" || exit 1

echo
echo "=== [3/7] compile the pure-Java core ==="
bash "$REPO/core/build.sh" || exit 1

echo
echo "=== [4/7] JVM self-test (splitter invariants) ==="
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$REPO/core/build/test-classes" \
    -cp "$REPO/core/build/classes" \
    "$REPO/core/src/test/java/dev/kokoro/tts/SplitterSelfTest.java" || exit 1
"$JAVA_HOME/bin/java" -cp "$REPO/core/build/classes:$REPO/core/build/test-classes" \
    dev.kokoro.tts.SplitterSelfTest || echo "(self-test reported failures)"

echo
echo "=== [5/7] BASELINE: naive 400-character splitter ==="
"$PY" "$HERE/splitter_conformance.py" --mode chars --budget 400 \
    --corpus "${CORPORA[@]}" \
    --out "$REPO/docs/research/conformance-baseline.json" --check-lossless 2>&1 \
    | grep -viE "$FILTER"

echo
echo "=== [6/7] PROTOTYPE: Kokoro-token-aware splitter ==="
"$PY" "$HERE/splitter_conformance.py" --mode model \
    --corpus "${CORPORA[@]}" \
    --out "$REPO/docs/research/conformance-model.json" --check-lossless 2>&1 \
    | grep -viE "$FILTER"

echo
echo "=== [7/7] probe the real Kokoro server ==="
"$PY" "$HERE/probe_real_server.py" --out "$REPO/docs/research/server-probe.json" 2>&1 \
    | grep -viE "$FILTER"

echo
echo "PIPELINE_DONE"

#!/usr/bin/env python3
"""Watch KokoroTts logs for playback stalls and attribute them.

The question a delayed utterance raises is: did the audio run out (delivery too
slow) or did the model simply put a long pause there? Those look similar to a
listener and completely different in the log, so this separates them.

For each utterance it reconstructs, per chunk, how much audio was delivered when,
then compares the **interval between deliveries** against the **playable duration**
already in hand. If the interval exceeds what was buffered, playback starves.

Usage:
    tools/watch_analyze.py .tooling/watch/phone.log [--server .tooling/watch/server.log]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

RATE = 24000
BYTES_PER_MS = RATE * 2 // 1000

CHUNK = re.compile(
    r"^(?P<ts>\d\d-\d\d \d\d:\d\d:\d\d\.\d+).*KokoroTts: chunk "
    r"(?P<idx>\d+)/(?P<total>\d+) chars=(?P<chars>\d+) est=(?P<est>\d+) "
    r"send=(?P<send>\d+)ms wait=(?P<wait>\d+)ms body=(?P<body>\d+)ms "
    r"bytes=(?P<bytes>\d+) trimFront=(?P<tf>\d+)ms trimBack=(?P<tb>\d+)ms"
    r"(?: cappedPauses=(?P<capped>\d+)ms)?")
SYNTH = re.compile(
    r"^(?P<ts>\d\d-\d\d \d\d:\d\d:\d\d\.\d+).*KokoroTts: synth ok: chars=(?P<chars>\d+) "
    r"chunks=(?P<chunks>\d+).*voice=(?P<voice>\S+).*firstAudio=(?P<first>-?\d+)ms")
RESOLVE = re.compile(r"KokoroTts: voice: default=(?P<d>\S+) requested=(?P<r>\S+) "
                     r"appsOverride=(?P<o>\w+) -> (?P<c>\S+) \((?P<why>[^)]+)\)")
BAD = re.compile(r"KokoroTts: (chunk \d+ failed|synthesis aborted|stopped|"
                 r"audioAvailable refused)|AndroidRuntime.*FATAL")
UNDERRUN = re.compile(r"[Uu]nderrun|AudioTrack.*starved|glitch", re.I)

TS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)")


def seconds(ts: str) -> float:
    h, m, rest = ts.split(" ")[1].split(":")
    return int(h) * 3600 + int(m) * 60 + float(rest)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("log", type=Path)
    ap.add_argument("--server", type=Path, default=None)
    args = ap.parse_args()

    if not args.log.exists() or args.log.stat().st_size == 0:
        print("no phone log captured yet")
        return 0

    lines = args.log.read_text(errors="replace").splitlines()
    utterances: list[dict] = []
    current: dict | None = None

    problems: list[str] = []
    underruns: list[str] = []
    voices: list[str] = []

    for line in lines:
        m = RESOLVE.search(line)
        if m:
            stamp = TS.match(line)
            voices.append(f"  {stamp.group(1) if stamp else '':<18} "
                          f"default={m.group('d')} requested={m.group('r')} "
                          f"override={m.group('o')} -> {m.group('c')} ({m.group('why')})")
            continue
        if UNDERRUN.search(line):
            underruns.append(line.strip()[:160])
        if BAD.search(line):
            problems.append(line.strip()[:160])

        m = CHUNK.search(line)
        if m:
            if current is None:
                current = {"chunks": []}
                utterances.append(current)
            current["chunks"].append({
                "t": seconds(m.group("ts")),
                "idx": int(m.group("idx")),
                "total": int(m.group("total")),
                "est": int(m.group("est")),
                "body": int(m.group("body")),
                "wait": int(m.group("wait")),
                "bytes": int(m.group("bytes")),
                "trim": int(m.group("tf")) + int(m.group("tb")),
                "capped": int(m.group("capped") or 0),
                "dur": (int(m.group("bytes")) //
                        BYTES_PER_MS) - int(m.group("tf")) - int(m.group("tb")),
            })
            continue
        m = SYNTH.search(line)
        if m and current is not None:
            current["voice"] = m.group("voice")
            current["first"] = int(m.group("first"))
            current["chars"] = int(m.group("chars"))
            current = None

    print(f"=== {len(utterances)} utterance(s), {len(lines)} log lines ===")
    worst = []
    for u in utterances:
        cs = u.get("chunks", [])
        if not cs:
            continue
        print(f"\n  voice={u.get('voice','?')} chars={u.get('chars','?')} "
              f"chunks={len(cs)} firstAudio={u.get('first','?')}ms")
        # Running buffer: how much playable audio is in hand when each chunk lands.
        buffered = 0
        for i, c in enumerate(cs):
            if i > 0:
                interval = c["t"] - cs[i - 1]["t"]
                buffered -= interval * 1000        # what played during the wait
                status = "ok"
                if buffered < 0:
                    status = f"STALL: ran {abs(buffered):.0f} ms dry"
                    worst.append((status, u.get("voice"), i))
                print(f"    chunk {c['idx']}/{c['total']} est={c['est']:>3} "
                      f"body={c['body']:>5}ms -> {c['dur']:>6}ms audio  "
                      f"delivered {interval:6.2f}s after the previous  {status}")
            else:
                print(f"    chunk {c['idx']}/{c['total']} est={c['est']:>3} "
                      f"body={c['body']:>5}ms -> {c['dur']:>6}ms audio  "
                      f"(first, {c['body']}ms before any sound)")
            buffered += c["dur"]

    print(f"\n=== voice decisions ({len(voices)}) ===")
    for v in voices[-10:]:
        print(v)

    print(f"\n=== underruns / glitches in the audio stack ({len(underruns)}) ===")
    for u in underruns[-10:]:
        print("  " + u)
    if not underruns:
        print("  none")

    print(f"\n=== errors ({len(problems)}) ===")
    for p in problems[-10:]:
        print("  " + p)
    if not problems:
        print("  none")

    if args.server and args.server.exists():
        text = args.server.read_text(errors="replace")
        done = re.findall(r"Split completed in ([\d.]+)ms, produced (\d+) chunks", text)
        reqs = re.findall(r'POST /v1/audio/speech HTTP/1.1" (\d+)', text)
        print(f"\n=== server ({len(reqs)} requests, {len(done)} completions) ===")
        for ms, n in done[-10:]:
            print(f"    synthesis {float(ms):7.0f} ms, {n} internal chunk(s)")
        if not done:
            print("  no synthesis completed yet")
    return 0


if __name__ == "__main__":
    sys.exit(main())

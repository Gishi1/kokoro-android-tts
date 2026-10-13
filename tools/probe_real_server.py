#!/usr/bin/env python3
"""Probe the real Kokoro server the driver will talk to.

Answers three questions with measurements rather than assumptions:

1. Does an over-long request fail, or does the server quietly chunk it?
2. How long does the client wait for the *first* audio byte when it sends the
   whole utterance at once?
3. How much of that wait disappears when the client splits and streams?

Writes docs/research/server-probe.json.

Usage:
    python3 tools/probe_real_server.py --url http://127.0.0.1:8880/v1/audio/speech
"""

from __future__ import annotations

import argparse
import json
import statistics
import struct
import sys
import time
import urllib.request
from pathlib import Path


def wav_duration(path: Path) -> float:
    """Seconds of audio in a RIFF/WAVE file, read from the data chunk size."""
    data = path.read_bytes()
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        return -1.0
    pos = 12
    channels = rate = bits = 1
    while pos + 8 <= len(data):
        cid = data[pos:pos + 4]
        size = struct.unpack("<I", data[pos + 4:pos + 8])[0]
        if cid == b"fmt ":
            _, channels, rate, _, _, bits = struct.unpack("<HHIIHH", data[pos + 8:pos + 24])
        elif cid == b"data":
            if size in (0xFFFFFFFF, 0) or pos + 8 + size > len(data):
                # Kokoro-FastAPI streams a WAV with a placeholder data size, so
                # fall back to the bytes actually present.
                size = len(data) - (pos + 8)
            return size / (rate * channels * bits / 8)
        pos += 8 + size + (size & 1)
    return -1.0


def post(url: str, payload: dict, timeout: float = 600.0):
    """POST and return (status, bytes, seconds_to_first_byte, seconds_total)."""
    body = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=body,
                                 headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            first = resp.read(1)
            ttfb = time.perf_counter() - t0
            rest = resp.read()
            return resp.status, first + rest, ttfb, time.perf_counter() - t0
    except urllib.error.HTTPError as e:
        return e.code, e.read(), time.perf_counter() - t0, time.perf_counter() - t0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:8880/v1/audio/speech")
    ap.add_argument("--voice", default="af_heart")
    ap.add_argument("--out", type=Path, default=Path("docs/research/server-probe.json"))
    ap.add_argument("--outdir", type=Path, default=Path(".tooling/probe"))
    args = ap.parse_args()
    args.outdir.mkdir(parents=True, exist_ok=True)

    # Import the client-side splitter so both sides use one definition of a chunk.
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from splitter_conformance import run_splitter  # noqa: E402

    results = {"url": args.url, "voice": args.voice, "probes": []}

    def record(name: str, text: str, path: Path, meta: dict | None = None) -> dict:
        status, data, ttfb, total = post(args.url, {
            "model": "kokoro", "input": text, "voice": args.voice,
            "response_format": "wav",
        })
        path.write_bytes(data)
        dur = wav_duration(path) if status == 200 else -1.0
        rec = {
            "name": name,
            "chars": len(text),
            "http": status,
            "bytes": len(data),
            "ttfb_s": round(ttfb, 3),
            "total_s": round(total, 3),
            "audio_seconds": round(dur, 3),
            "realtime_factor": round(total / dur, 3) if dur > 0 else None,
        }
        if meta:
            rec.update(meta)
        results["probes"].append(rec)
        print(f"  {name:<28} http={status} chars={len(text):>5} "
              f"ttfb={ttfb:6.3f}s total={total:7.3f}s audio={dur:7.2f}s")
        return rec

    print("== 1. does the server reject or silently chunk an over-long request? ==")
    cjk = ("这是一个用于测试语音合成系统的很长的中文句子它包含了很多汉字"
           "用来验证分词逻辑是否正确")
    record("cjk_42chars_english_voice", cjk, args.outdir / "probe_cjk.wav",
           {"note": "545 phoneme tokens through the English G2P; well over the 510 limit"})

    wall = ("the quick brown fox jumps over the lazy dog and then keeps running "
            "without any pause because there is no punctuation here at all so the "
            "splitter must fall back to words ") * 6
    record("no_punctuation_wall", wall.strip(), args.outdir / "probe_wall.wav")

    prose = ("Elizabeth could not help noticing how frequently the conversation "
             "returned to the same subject, and how little either of them seemed "
             "willing to let it rest. ") * 12
    prose = prose.strip()

    print("\n== 2. whole utterance in one request (what a naive client does) ==")
    whole = record("whole_utterance", prose, args.outdir / "probe_whole.wav")

    print("\n== 3. the same text split by the Kokoro-aware splitter, streamed ==")
    # Split via the Java CLI on a temp corpus, so the chunks measured here are
    # produced by the same code the Android driver runs.
    tmp = args.outdir / "prose.txt"
    tmp.write_text(prose, encoding="utf-8")
    chunks = run_splitter("model", [tmp], 400, 360, 240, 360)
    print(f"  splitter produced {len(chunks)} chunks; "
          f"estimated tokens {[c['est'] for c in chunks]}")

    first_chunk_bytes = 0
    ttfb_list = []
    total = 0.0
    for c in chunks:
        status, data, ttfb, secs = post(args.url, {
            "model": "kokoro", "input": c["text"], "voice": args.voice,
            "response_format": "wav",
        })
        if status != 200:
            print(f"  chunk {c['index']} FAILED http={status}")
            results["chunk_failure"] = {"index": c["index"], "http": status,
                                        "est": c["est"], "chars": len(c["text"])}
            break
        if c["index"] == 0:
            first_chunk_bytes = len(data)
        ttfb_list.append(ttfb)
        total += secs

    streamed = {
        "name": "split_and_streamed",
        "chars": len(prose),
        "chunks": len(chunks),
        "first_chunk_ready_s": round(ttfb_list[0], 3) if ttfb_list else None,
        "median_chunk_s": round(statistics.median(ttfb_list), 3) if ttfb_list else None,
        "total_s": round(total, 3),
        "audio_seconds": whole["audio_seconds"],
        "realtime_factor": round(total / whole["audio_seconds"], 3)
        if whole["audio_seconds"] > 0 else None,
    }
    results["probes"].append(streamed)
    results["headline"] = {
        "whole_utterance_ttfb_s": whole["ttfb_s"],
        "first_chunk_ttfb_s": streamed["first_chunk_ready_s"],
        "time_to_first_audio_speedup": round(
            whole["ttfb_s"] / streamed["first_chunk_ready_s"], 2)
        if streamed["first_chunk_ready_s"] else None,
        "whole_total_s": whole["total_s"],
        "streamed_total_s": streamed["total_s"],
    }
    print(f"\n  whole-utterance time-to-first-byte : {whole['ttfb_s']:.3f}s")
    print(f"  first chunk ready after            : {streamed['first_chunk_ready_s']:.3f}s")
    print(f"  -> time-to-first-audio speedup     : "
          f"{results['headline']['time_to_first_audio_speedup']}x")
    print(f"  whole total {whole['total_s']:.2f}s vs split total "
          f"{streamed['total_s']:.2f}s")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(results, indent=2))
    print(f"\nwrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

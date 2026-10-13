#!/usr/bin/env python3
"""Minimal OpenAI-compatible Kokoro TTS server for driver testing.

Implements `POST /v1/audio/speech` with the same request/response contract as
Kokoro-FastAPI, so the Android driver is tested against the real interface
rather than a private one.

Two modes, and the difference is the point of the experiment:

  --mode truncate   behave like upstream `KPipeline`: synthesise the first 510
                    phoneme tokens and silently drop the rest (kokoro/pipeline.py:288).
                    This is the "garbled / cut off" failure the driver exists to avoid.
  --mode strict     reject any request whose phonemes exceed 510 with HTTP 400.
                    A driver that splits correctly never sees this error.

Usage:
    .tooling/kokoro-venv/bin/python tools/kokoro_server.py --port 8880 --mode strict
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = {"pipeline": None, "lock": threading.Lock(), "mode": "strict",
         "lang": "a", "requests": 0, "rejected": 0, "truncated": 0,
         "token_hist": []}

MODEL_HARD_LIMIT = 510


def phonemize(text: str):
    """Return (phoneme_string, token_count) using the real Kokoro G2P."""
    from kokoro import KPipeline
    p = STATE["pipeline"]
    ps, tk = p.g2p(text)
    if tk is None:
        return ps.strip(), len(ps.strip())
    return KPipeline.tokens_to_ps(tk), len(KPipeline.tokens_to_ps(tk))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # quieter default logging
        if STATE.get("verbose"):
            sys.stderr.write("  %s\n" % (fmt % args))

    def do_GET(self):
        if self.path in ("/health", "/v1/models"):
            body = json.dumps({"status": "ok", "mode": STATE["mode"],
                               "requests": STATE["requests"],
                               "rejected": STATE["rejected"],
                               "truncated": STATE["truncated"]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path == "/stats":
            body = json.dumps({
                "requests": STATE["requests"],
                "rejected": STATE["rejected"],
                "truncated": STATE["truncated"],
                "token_hist": STATE["token_hist"],
                "mode": STATE["mode"],
            }).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_error(404)

    def do_POST(self):
        if self.path != "/v1/audio/speech":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        try:
            req = json.loads(raw)
        except Exception:
            self._json(400, {"error": "invalid json"})
            return

        text = req.get("input", "")
        voice = req.get("voice", "af_heart")
        speed = float(req.get("speed", 1.0))
        fmt = req.get("response_format", "pcm")

        STATE["requests"] += 1
        t0 = time.time()
        try:
            ps, ntokens = phonemize(text)
        except Exception as exc:  # noqa: BLE001
            self._json(500, {"error": f"phonemize failed: {exc}"})
            return

        STATE["token_hist"].append(ntokens)

        if ntokens > MODEL_HARD_LIMIT:
            if STATE["mode"] == "strict":
                STATE["rejected"] += 1
                sys.stderr.write(
                    f"  REJECT {ntokens} tokens (limit {MODEL_HARD_LIMIT}) "
                    f"chars={len(text)}: {text[:60]!r}\n")
                self._json(400, {
                    "error": {
                        "message": f"Phoneme string too long: {ntokens} > 510",
                        "type": "invalid_request_error",
                        "code": "input_too_long",
                    }
                })
                return
            # truncate mode mirrors pipeline.py:288
            STATE["truncated"] += 1
            sys.stderr.write(
                f"  TRUNCATE {ntokens} -> {MODEL_HARD_LIMIT} tokens "
                f"chars={len(text)}: {text[:60]!r}\n")
            ps = ps[:MODEL_HARD_LIMIT]

        try:
            import numpy as np
            import torch
            with STATE["lock"]:
                audio = STATE["pipeline"](ps, voice=voice, speed=speed)
                chunks = [a for a in audio]
            if not chunks:
                self._json(500, {"error": "no audio produced"})
                return
            wav = torch.cat([c.audio if hasattr(c, "audio") else c for c in chunks])
            pcm = (wav.numpy() * 32767.0).astype(np.int16).tobytes()
        except Exception as exc:  # noqa: BLE001
            import traceback
            traceback.print_exc()
            self._json(500, {"error": f"synthesis failed: {exc}"})
            return

        if fmt == "wav":
            pcm = wrap_wav(pcm, 24000, 1)
            ctype = "audio/wav"
        else:
            ctype = "audio/pcm"

        elapsed = time.time() - t0
        sys.stderr.write(
            f"  OK tokens={ntokens} chars={len(text)} bytes={len(pcm)} "
            f"{elapsed * 1000:.0f}ms voice={voice}\n")

        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(pcm)))
        self.end_headers()
        self.wfile.write(pcm)

    def _json(self, code: int, obj) -> None:
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def wrap_wav(pcm: bytes, rate: int, channels: int) -> bytes:
    import struct
    bits = 16
    byte_rate = rate * channels * bits // 8
    header = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVE"
    header += b"fmt " + struct.pack("<IHHIIHH", 16, 1, channels, rate,
                                    byte_rate, channels * bits // 8, bits)
    header += b"data" + struct.pack("<I", len(pcm))
    return header + pcm


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8880)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--mode", choices=["strict", "truncate"], default="strict")
    ap.add_argument("--lang", default="a")
    ap.add_argument("--repo-id", default="hexgrad/Kokoro-82M")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    STATE["mode"] = args.mode
    STATE["lang"] = args.lang
    STATE["verbose"] = args.verbose

    from kokoro import KPipeline
    print(f"loading Kokoro weights ({args.repo_id})...", flush=True)
    STATE["pipeline"] = KPipeline(lang_code=args.lang, repo_id=args.repo_id)
    print("model ready", flush=True)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"listening on http://{args.host}:{args.port}  mode={args.mode}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())

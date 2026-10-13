#!/usr/bin/env python3
"""Build the Kokoro TTS engine APK without Gradle.

Gradle is not installed on this machine (only unbuilt .drv files exist in the
nix store), and pulling Android Gradle Plugin plus its dependency graph for a
single-service app is a large, fragile download. The SDK's own build tools are
sufficient and deterministic:

    aapt2 compile -> aapt2 link -> javac -> d8 -> zip -> zipalign -> apksigner

The core splitter is compiled from ../core/src/main/java so the APK and the
off-device unit tests share one source of truth.

Usage:
    python3 android/build.py [--release]
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
TOOLING = REPO / ".tooling"
SDK = TOOLING / "android-sdk"
BUILD = ROOT / "build"
JAVA_HOME = os.environ.get(
    "JAVA_HOME",
    "/nix/store/5badkg3gmzg1c29akwglknkizfg6zj0g-openjdk-17.0.17+8/lib/openjdk")

PLATFORM = SDK / "platforms" / "android-34" / "android.jar"
BT = SDK / "build-tools" / "34.0.0"
AAPT2 = BT / "aapt2"
D8 = BT / "d8"
ZIPALIGN = BT / "zipalign"
APKSIGNER = BT / "apksigner"
KEYTOOL = Path(JAVA_HOME) / "bin" / "keytool"

APP_ID = "dev.kokoro.tts"
VERSION_CODE = "1"
VERSION_NAME = "1.0"


def build_env() -> dict:
    """The SDK's launcher scripts need `java` on PATH (d8/apksigner are scripts)."""
    env = dict(os.environ)
    env["JAVA_HOME"] = str(JAVA_HOME)
    env["PATH"] = str(Path(JAVA_HOME) / "bin") + os.pathsep + env.get("PATH", "")
    return env


def run(cmd: list, **kw) -> None:
    printable = " ".join(str(c) for c in cmd)
    print(f"  $ {printable[:160]}{'...' if len(printable) > 160 else ''}")
    kw.setdefault("env", build_env())
    proc = subprocess.run([str(c) for c in cmd], **kw)
    if proc.returncode != 0:
        raise SystemExit(f"command failed ({proc.returncode}): {printable}")


# SplitterCli uses java.nio.file and exists only to drive the splitter from a
# JVM harness; shipping it would cost dex size and minSdk 21 compatibility.
EXCLUDE = {"SplitterCli.java"}


def java_sources() -> list:
    srcs = [p for p in (ROOT / "src" / "main" / "java").rglob("*.java")
            if p.name not in EXCLUDE]
    srcs += [p for p in (REPO / "core" / "src" / "main" / "java").rglob("*.java")
             if p.name not in EXCLUDE]
    return srcs


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--release", action="store_true",
                    help="keystore/signing uses the debug key either way; reserved")
    args = ap.parse_args()

    for tool in (AAPT2, D8, ZIPALIGN, APKSIGNER, KEYTOOL):
        if not tool.exists():
            raise SystemExit(f"missing build tool: {tool}")
    if not PLATFORM.exists():
        raise SystemExit(f"missing android.jar: {PLATFORM}")

    if BUILD.exists():
        shutil.rmtree(BUILD)
    (BUILD / "res").mkdir(parents=True)
    (BUILD / "gen").mkdir(parents=True)
    (BUILD / "classes").mkdir(parents=True)
    (BUILD / "dex").mkdir(parents=True)

    # 1. resources
    print("[1/7] aapt2 compile")
    run([AAPT2, "compile", "--dir", ROOT / "res", "-o", BUILD / "res.zip"])

    # 2. link resources + manifest into a base APK, generating R.java
    print("[2/7] aapt2 link")
    run([AAPT2, "link",
         "-o", BUILD / "base.apk",
         "-I", PLATFORM,
         "--manifest", ROOT / "AndroidManifest.xml",
         "-R", BUILD / "res.zip",
         "--java", BUILD / "gen",
         "--min-sdk-version", "21",
         "--target-sdk-version", "34",
         "--version-code", VERSION_CODE,
         "--version-name", VERSION_NAME,
         "--auto-add-overlay"])

    # 3. compile Java (core + android) against android.jar
    print("[3/7] javac")
    srcs = java_sources() + list((BUILD / "gen").rglob("*.java"))
    print(f"      {len(srcs)} source files")
    argfile = BUILD / "javac.args"
    argfile.write_text("\n".join(f'"{s}"' for s in srcs))
    run([Path(JAVA_HOME) / "bin" / "javac",
         "-source", "8", "-target", "8",
         "-bootclasspath", PLATFORM,
         "-classpath", PLATFORM,
         "-encoding", "UTF-8",
         "-nowarn",
         "-d", BUILD / "classes",
         f"@{argfile}"])

    # 4. jar the classes so d8 sees a single input
    print("[4/7] jar classes")
    classes_jar = BUILD / "classes.jar"
    with zipfile.ZipFile(classes_jar, "w", zipfile.ZIP_DEFLATED) as z:
        for cls in (BUILD / "classes").rglob("*.class"):
            z.write(cls, cls.relative_to(BUILD / "classes"))

    # 5. dex
    print("[5/7] d8")
    run([D8, "--lib", PLATFORM, "--min-api", "21",
         "--output", BUILD / "dex", classes_jar])

    # 6. put classes.dex into the APK
    print("[6/7] package + zipalign")
    unsigned = BUILD / "unsigned.apk"
    with zipfile.ZipFile(BUILD / "base.apk", "r") as src, \
            zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as dst:
        for item in src.infolist():
            dst.writestr(item, src.read(item.filename))
        dst.write(BUILD / "dex" / "classes.dex", "classes.dex")

    aligned = BUILD / "aligned.apk"
    run([ZIPALIGN, "-f", "-p", "4", unsigned, aligned])

    # 7. sign
    print("[7/7] sign")
    # Kept OUTSIDE build/ so a clean rebuild keeps the same signature and
    # `adb install -r` upgrades in place instead of failing on a changed cert.
    keystore = ROOT / "debug.keystore"
    if not keystore.exists():
        run([KEYTOOL, "-genkeypair", "-v",
             "-keystore", keystore,
             "-alias", "androiddebugkey",
             "-storepass", "android", "-keypass", "android",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=Android Debug,O=Android,C=US"])
    signed = ROOT / "kokoro-tts.apk"
    run([APKSIGNER, "sign",
         "--ks", keystore,
         "--ks-pass", "pass:android",
         "--key-pass", "pass:android",
         "--ks-key-alias", "androiddebugkey",
         "--out", signed, aligned])
    run([APKSIGNER, "verify", "--print-certs", signed])

    print(f"\nbuilt {signed} ({signed.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

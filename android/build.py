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
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
TOOLING = REPO / ".tooling"
BUILD = ROOT / "build"


def env(name: str, default: str = "") -> str:
    """Read an env var, treating an empty value as unset.

    CI passes unset secrets through as empty strings, which would otherwise
    override the defaults with '' -- an empty keystore password, say.
    """
    return os.environ.get(name) or default


MIN_SDK = 21
# Preferred compileSdk. A lower platform still builds; a higher one is used as-is.
WANT_SDK = 34
# ...unless pinned, which is what CI does: a build machine usually has several
# platforms installed, and "newest" would silently move compileSdk/targetSdk.
PIN_BUILD_TOOLS = env("ANDROID_BUILD_TOOLS")
PIN_PLATFORM = env("ANDROID_PLATFORM")


def version_key(name: str) -> tuple:
    """'34.0.0' -> (34, 0, 0); a non-numeric part sorts below any number."""
    return tuple(int(p) if p.isdigit() else -1 for p in re.split(r"[._-]", name))


def find_sdk() -> Path:
    """$ANDROID_HOME, then $ANDROID_SDK_ROOT, then a local .tooling checkout."""
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(var)
        if value and Path(value).is_dir():
            return Path(value)
    return TOOLING / "android-sdk"


def newest_with(parent: Path, *required: str):
    """Highest-versioned subdirectory of `parent` holding all of `required`."""
    if not parent.is_dir():
        return None
    candidates = [d for d in parent.iterdir()
                  if d.is_dir() and all((d / r).exists() for r in required)]
    return max(candidates, key=lambda d: version_key(d.name), default=None)


def find_platform(sdk: Path):
    """Newest installed android-<N>, preferring one at or above WANT_SDK."""
    platforms = sdk / "platforms"
    if not platforms.is_dir():
        return None
    candidates = [d for d in platforms.iterdir()
                  if d.is_dir() and re.fullmatch(r"android-\d+", d.name)
                  and (d / "android.jar").exists()]
    if not candidates:
        return None
    api = lambda d: int(d.name.split("-")[1])
    modern = [d for d in candidates if api(d) >= WANT_SDK]
    return max(modern or candidates, key=api)


def find_java_home():
    """$JAVA_HOME, else the JDK that owns the `javac` on PATH."""
    env = os.environ.get("JAVA_HOME")
    if env and (Path(env) / "bin" / "javac").exists():
        return Path(env)
    javac = shutil.which("javac")
    if javac:
        # .../lib/openjdk/bin/javac -> .../lib/openjdk   (NixOS)
        # .../jdk-17/bin/javac      -> .../jdk-17        (ordinary installs)
        return Path(javac).resolve().parent.parent
    return None


SDK = find_sdk()
BT = ((SDK / "build-tools" / PIN_BUILD_TOOLS) if PIN_BUILD_TOOLS
      else newest_with(SDK / "build-tools", "aapt2", "d8", "zipalign", "apksigner"))
PLATFORM_DIR = ((SDK / "platforms" / PIN_PLATFORM) if PIN_PLATFORM
                else find_platform(SDK))
PLATFORM = (PLATFORM_DIR / "android.jar") if PLATFORM_DIR else None
TARGET_SDK = int(PLATFORM_DIR.name.split("-")[1]) if PLATFORM_DIR else WANT_SDK
JAVA_HOME = find_java_home()

AAPT2 = (BT / "aapt2") if BT else None
D8 = (BT / "d8") if BT else None
ZIPALIGN = (BT / "zipalign") if BT else None
APKSIGNER = (BT / "apksigner") if BT else None
KEYTOOL = (JAVA_HOME / "bin" / "keytool") if JAVA_HOME else None

APP_ID = "dev.kokoro.tts"
# CI sets these so a tagged release reports its own version; Android wants an
# integer versionCode that only ever increases, so callers pass a run number.
VERSION_CODE = env("APP_VERSION_CODE", "1")
VERSION_NAME = env("APP_VERSION_NAME", "1.0")

# Signing. The default is the throwaway debug key this script generates, which is
# fine for sideloading but is regenerated per checkout, so its signature is not
# stable across machines. A release passes a real keystore through these instead.
KEYSTORE = Path(env("KEYSTORE_PATH", str(ROOT / "debug.keystore")))
KEYSTORE_PASS = env("KEYSTORE_PASS", "android")
KEY_ALIAS = env("KEY_ALIAS", "androiddebugkey")
KEY_PASS = env("KEY_PASS", KEYSTORE_PASS)


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

    if not SDK.is_dir():
        raise SystemExit(
            "no Android SDK found. Set ANDROID_HOME (or ANDROID_SDK_ROOT), or "
            f"install one into {TOOLING / 'android-sdk'}")
    if JAVA_HOME is None:
        raise SystemExit(
            "no JDK found. Set JAVA_HOME, or put `javac` on PATH "
            "(the SDK's d8/apksigner are scripts that need `java`)")
    for label, tool in (("build-tools", BT), ("android.jar", PLATFORM),
                        ("keytool", KEYTOOL), ("aapt2", AAPT2), ("d8", D8),
                        ("zipalign", ZIPALIGN), ("apksigner", APKSIGNER)):
        if tool is None or not Path(tool).exists():
            raise SystemExit(f"missing {label}: {tool}")

    print(f"SDK        {SDK}")
    print(f"build-tools {BT.name}")
    print(f"platform   {PLATFORM_DIR.name}")
    print(f"JDK        {JAVA_HOME}")

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
         "--min-sdk-version", str(MIN_SDK),
         "--target-sdk-version", str(TARGET_SDK),
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
    run([D8, "--lib", PLATFORM, "--min-api", str(MIN_SDK),
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
    # The default keystore is kept OUTSIDE build/ so a clean rebuild keeps the same
    # signature and `adb install -r` upgrades in place instead of failing on a
    # changed cert. It is only generated when absent, so a keystore supplied through
    # KEYSTORE_PATH (CI, decoding a stored release key) is used exactly as given.
    keystore = KEYSTORE
    keystore.parent.mkdir(parents=True, exist_ok=True)
    if not keystore.exists():
        print(f"      generating a throwaway debug key at {keystore}")
        run([KEYTOOL, "-genkeypair", "-v",
             "-keystore", keystore,
             "-alias", KEY_ALIAS,
             "-storepass", KEYSTORE_PASS, "-keypass", KEY_PASS,
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=Android Debug,O=Android,C=US"])
    signed = ROOT / "kokoro-tts.apk"
    run([APKSIGNER, "sign",
         "--ks", keystore,
         "--ks-pass", f"pass:{KEYSTORE_PASS}",
         "--key-pass", f"pass:{KEY_PASS}",
         "--ks-key-alias", KEY_ALIAS,
         "--out", signed, aligned])
    run([APKSIGNER, "verify", "--print-certs", signed])

    print(f"\nbuilt {signed} ({signed.stat().st_size} bytes) "
          f"versionName={VERSION_NAME} versionCode={VERSION_CODE} "
          f"targetSdk={TARGET_SDK}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

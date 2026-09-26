"""Fail a diagnostic APK build if its JNI bridge cannot run the frame proof.

This checks the *single* packaged native library. Finding a function in an
unpackaged or separately built library is not sufficient: Gyroflow's manager
state must belong to the same build that performs the pixel transformation.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


PREFIX = "Java_jp_sakaguchi_dancerecenter_GyroflowBridge_"
REQUIRED = (
    "nativeInit",
    "nativeInspectZV1",
    "nativePrepareStabilization",
    "nativeStabilizeFrame",
)
LIBRARY = "lib/arm64-v8a/libdance_gyroflow_jni.so"


def exported_symbols(so: Path) -> set[str]:
    output = subprocess.check_output(["readelf", "--dyn-syms", "--wide", str(so)], text=True)
    return set(re.findall(r"\b(Java_jp_sakaguchi_dancerecenter_GyroflowBridge_\w+)\b", output))


def check(path: Path) -> int:
    if path.suffix == ".apk":
        with zipfile.ZipFile(path) as archive:
            if LIBRARY not in archive.namelist():
                print(f"FAIL: {LIBRARY} が APK にありません")
                return 1
            with tempfile.TemporaryDirectory() as tmp:
                native = Path(tmp) / "libdance_gyroflow_jni.so"
                native.write_bytes(archive.read(LIBRARY))
                symbols = exported_symbols(native)
    else:
        symbols = exported_symbols(path)

    missing = [name for name in REQUIRED if PREFIX + name not in symbols]
    if missing:
        print(f"FAIL: {path.name}: JNI 関数が不足: {', '.join(missing)}")
        return 1
    print(f"PASS: {path.name}: 必要な JNI 関数 {len(REQUIRED)} 個が一つのライブラリにあります")
    print("実機でのジャイロ解析と画素変形は別途確認してください。")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path, help="Android APK または arm64 .so")
    args = parser.parse_args()
    sys.exit(check(args.artifact))

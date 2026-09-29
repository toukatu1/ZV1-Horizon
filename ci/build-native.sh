#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
project_root="$PWD"
ndk_root="${ANDROID_HOME:?ANDROID_HOME is required}/ndk/${NDK_VERSION:-27.3.13750724}"
ndk_bin="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk_bin/aarch64-linux-android29-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$ndk_bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
test -x "$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
rustc +stable --version
cargo +stable build --locked --release --target aarch64-linux-android \
  --manifest-path native-build/gyro-probe-unified/Cargo.toml
library=native-build/gyro-probe-unified/target/aarch64-linux-android/release/libdance_gyroflow_jni.so
python3 ci/verify_gyro_probe.py "$library"
python3 ci/verify-v15.py "$library"
mkdir -p artifacts
cp "$library" artifacts/libdance_gyroflow_jni.so
python3 - <<'PY'
import hashlib,json,os,subprocess
from pathlib import Path
root=Path('.')
digest=hashlib.sha256()
for p in sorted((root/'native-build').rglob('*')):
    if p.is_file() and 'target' not in p.parts:
        digest.update(p.as_posix().encode()+b'\0'+p.read_bytes()+b'\0')
so=root/'artifacts/libdance_gyroflow_jni.so'
data={'commit':os.environ.get('GITHUB_SHA'),
      'source_sha256':digest.hexdigest(),
      'library_sha256':hashlib.sha256(so.read_bytes()).hexdigest(),
      'rustc':subprocess.check_output(['rustc','+stable','--version'],text=True).strip(),
      'ndk':os.environ.get('NDK_VERSION','27.3.13750724'),
      'target':'aarch64-linux-android','horizon_percent':0,
      'smoothness':[0.25,0.5,1.0],
      'validation':'ELF/JNI/version markers only; Android execution not yet tested'}
(root/'artifacts/build-info.json').write_text(json.dumps(data,indent=2)+'\n')
(root/'artifacts/SHA256SUMS').write_text(data['library_sha256']+'  libdance_gyroflow_jni.so\n')
PY

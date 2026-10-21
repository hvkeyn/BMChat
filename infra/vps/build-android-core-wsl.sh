#!/bin/sh
# Build the Android arm64 Rust core + libnative-utils.so inside WSL.
# Usage: wsl -d Ubuntu bash /mnt/e/PPROJECTS/BMChat/infra/vps/build-android-core-wsl.sh
set -e
export PATH="/root/.cargo/bin:$PATH"
export ANDROID_NDK_ROOT=/opt/android-ndk-r27b
export CARGO_TARGET_DIR=/tmp/deltachat-build
cd /mnt/e/PPROJECTS/BMChat/clients/android
bash scripts/ndk-make.sh arm64-v8a

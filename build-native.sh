#!/bin/sh
# Builds libfm1mix.so for arm64 phones into app/src/main/jniLibs.
# Needs: rustup (stable, recent: fm1-core uses `if let` guards), the Android NDK
# (ANDROID_NDK_HOME set), and `cargo install cargo-ndk`.
set -e
[ -d third_party/fm1-emu/crates ] || git submodule update --init --recursive
rustup target add aarch64-linux-android
cd native
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release

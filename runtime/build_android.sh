#!/usr/bin/env bash
# Builds libp2l_runtime.so for Android (arm64-v8a, armeabi-v7a, x86_64) with the NDK's clang as the linker, into
# target/android/<abi>/. Needs `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android`
# and an NDK: ANDROID_NDK_HOME, or the newest under ANDROID_HOME/ndk. API level 21 unless ANDROID_API is set.
set -euo pipefail
cd "$(dirname "$0")"
ndk="${ANDROID_NDK_HOME:-}"
if [ -z "$ndk" ] && [ -n "${ANDROID_HOME:-}" ]; then ndk="$(ls -d "$ANDROID_HOME"/ndk/*/ 2>/dev/null | sort -V | tail -1)"; fi
[ -n "$ndk" ] && [ -d "$ndk" ] || { echo "No Android NDK: set ANDROID_NDK_HOME" >&2; exit 1; }
host="$(ls "$ndk/toolchains/llvm/prebuilt" | head -1)"
bin="$ndk/toolchains/llvm/prebuilt/$host/bin"
api="${ANDROID_API:-21}"
suffix=""; case "$host" in windows*) suffix=".cmd" ;; esac
for spec in "aarch64-linux-android arm64-v8a aarch64-linux-android" "armv7-linux-androideabi armeabi-v7a armv7a-linux-androideabi" "x86_64-linux-android x86_64 x86_64-linux-android"; do
	set -- $spec
	target="$1" abi="$2" clang="$bin/$3$api-clang$suffix"
	var="CARGO_TARGET_$(echo "$target" | tr 'a-z-' 'A-Z_')_LINKER"
	env "$var=$clang" cargo build --release --lib --target "$target"
	mkdir -p "target/android/$abi"
	cp "target/$target/release/libp2l_runtime.so" "target/android/$abi/"
	echo "target/android/$abi/libp2l_runtime.so"
done

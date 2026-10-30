#!/usr/bin/env bash
# AI 자막 엔진을 안드로이드(arm64)용으로 빌드해서 app/src/main/jniLibs에 넣는다.
#  - whisper_jni / llama_jni : 기본판 (모든 최신 폰)
#  - whisper_jni_i8mm / llama_jni_i8mm : 고성능판 (AI 계산 명령어 i8mm 지원 폰 — 앱이 자동으로 고름)
#  - audio_jni : 모든 영상에서 소리를 꺼내는 경량 FFmpeg
set -euo pipefail
cd "$(dirname "$0")"
WHISPER_TAG=v1.7.6
LLAMA_TAG=b5600
FFMPEG_TAG=n7.1
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
[ -n "$NDK" ] || { echo "NDK 경로가 없어요"; exit 1; }
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
SRC="$PWD/.src"
OUT="$PWD/../app/src/main/jniLibs/arm64-v8a"
mkdir -p "$SRC" "$OUT"
[ -d "$SRC/whisper.cpp" ] || git clone -q --depth 1 --branch "$WHISPER_TAG" https://github.com/ggml-org/whisper.cpp "$SRC/whisper.cpp"
[ -d "$SRC/llama.cpp" ] || git clone -q --depth 1 --branch "$LLAMA_TAG" https://github.com/ggml-org/llama.cpp "$SRC/llama.cpp"
[ -d "$SRC/ffmpeg" ] || git clone -q --depth 1 --branch "$FFMPEG_TAG" https://github.com/FFmpeg/FFmpeg "$SRC/ffmpeg"

android=(
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI=arm64-v8a
  -DANDROID_PLATFORM=android-26
  -DANDROID_STL=c++_static
  -DCMAKE_BUILD_TYPE=Release
  -G Ninja
)

build_ai() { # $1 = 명령어 세트, $2 = 이름 뒤에 붙일 것
  local arch="$1" sfx="$2"
  local flags=(-DGGML_CPU_ARM_ARCH="$arch" -DCMAKE_C_FLAGS="-march=$arch -O3" -DCMAKE_CXX_FLAGS="-march=$arch -O3" -DJNI_SUFFIX="$sfx")
  cmake -S whisper -B ".build/whisper$sfx" -DWHISPER_SRC="$SRC/whisper.cpp" "${android[@]}" "${flags[@]}"
  cmake --build ".build/whisper$sfx" --target whisper_jni -j"$(nproc)"
  cmake -S llama -B ".build/llama$sfx" -DLLAMA_SRC="$SRC/llama.cpp" "${android[@]}" "${flags[@]}"
  cmake --build ".build/llama$sfx" --target llama_jni -j"$(nproc)"
  cp ".build/whisper$sfx/libwhisper_jni$sfx.so" ".build/llama$sfx/libllama_jni$sfx.so" "$OUT/"
}
build_ai "armv8.2-a+dotprod+fp16" ""
build_ai "armv8.2-a+dotprod+fp16+i8mm" "_i8mm"

# 소리 꺼내기용 FFmpeg (정적 라이브러리)
source audio/ffmpeg_flags.sh
FF_OUT="$PWD/.build/ffmpeg-out"
if [ ! -f "$FF_OUT/lib/libavformat.a" ]; then
  pushd "$SRC/ffmpeg" >/dev/null
  ./configure --prefix="$FF_OUT" --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile \
    --cc="$TC/bin/aarch64-linux-android26-clang" --cxx="$TC/bin/aarch64-linux-android26-clang++" \
    --ar="$TC/bin/llvm-ar" --nm="$TC/bin/llvm-nm" --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip" \
    --sysroot="$TC/sysroot" --extra-cflags="-O3 -fPIC" "${FFMPEG_FLAGS[@]}"
  make -j"$(nproc)"
  make install
  popd >/dev/null
fi
cmake -S audio -B .build/audio -DFFMPEG_DIR="$FF_OUT" "${android[@]}"
cmake --build .build/audio -j"$(nproc)"
cp .build/audio/libaudio_jni.so "$OUT/"

"$TC/bin/llvm-strip" --strip-unneeded "$OUT"/*.so
ls -la "$OUT"

#!/usr/bin/env bash
# AI 자막 엔진(whisper.cpp, llama.cpp)을 안드로이드(arm64)용으로 빌드해서 app/src/main/jniLibs에 넣는다.
set -euo pipefail
cd "$(dirname "$0")"
WHISPER_TAG=v1.7.6
LLAMA_TAG=b5600
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
[ -n "$NDK" ] || { echo "NDK 경로가 없어요"; exit 1; }
SRC="$PWD/.src"
OUT="$PWD/../app/src/main/jniLibs/arm64-v8a"
mkdir -p "$SRC" "$OUT"
[ -d "$SRC/whisper.cpp" ] || git clone -q --depth 1 --branch "$WHISPER_TAG" https://github.com/ggml-org/whisper.cpp "$SRC/whisper.cpp"
[ -d "$SRC/llama.cpp" ] || git clone -q --depth 1 --branch "$LLAMA_TAG" https://github.com/ggml-org/llama.cpp "$SRC/llama.cpp"

# S24 울트라·폴드5(ARMv9) 모두 지원하는 명령어 세트로 최적화
ARCH="armv8.2-a+dotprod+fp16"
common=(
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI=arm64-v8a
  -DANDROID_PLATFORM=android-26
  -DANDROID_STL=c++_static
  -DCMAKE_BUILD_TYPE=Release
  -DGGML_CPU_ARM_ARCH="$ARCH"
  -DCMAKE_C_FLAGS="-march=$ARCH -O3"
  -DCMAKE_CXX_FLAGS="-march=$ARCH -O3"
  -G Ninja
)
cmake -S whisper -B .build/whisper -DWHISPER_SRC="$SRC/whisper.cpp" "${common[@]}"
cmake --build .build/whisper --target whisper_jni -j"$(nproc)"
cmake -S llama -B .build/llama -DLLAMA_SRC="$SRC/llama.cpp" "${common[@]}"
cmake --build .build/llama --target llama_jni -j"$(nproc)"

cp .build/whisper/libwhisper_jni.so "$OUT/"
cp .build/llama/libllama_jni.so "$OUT/"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$OUT/libwhisper_jni.so" "$OUT/libllama_jni.so"
ls -la "$OUT"

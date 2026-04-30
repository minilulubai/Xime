#!/bin/bash
# 构建 sherpa-onnx JNI 库（仅流式 ASR 功能）
# 来源: https://github.com/k2-fsa/sherpa-onnx
# 许可证: Apache License 2.0

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SHERPA_ONNX_VERSION="1.13.0"
SHERPA_ONNX_TAR_URL="https://github.com/k2-fsa/sherpa-onnx/archive/refs/tags/v${SHERPA_ONNX_VERSION}.tar.gz"

APP_DIR="${SCRIPT_DIR}/app"
JNI_DIR="${APP_DIR}/src/main/jni"
JNI_LIBS_DIR="${APP_DIR}/src/main/jniLibs"

if [ -z "$ANDROID_NDK" ]; then
    if [ -z "$ANDROID_SDK_ROOT" ]; then
        ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
    fi
    if [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
        ANDROID_NDK=$(ls -d "$ANDROID_SDK_ROOT/ndk/"* | sort -V | tail -1)
    fi
fi

if [ -z "$ANDROID_NDK" ] || [ ! -d "$ANDROID_NDK" ]; then
    echo "ERROR: ANDROID_NDK not found"
    echo "Please set ANDROID_NDK environment variable"
    exit 1
fi

echo "=== Building sherpa-onnx JNI (minimal ASR only) ==="
echo "ANDROID_NDK: $ANDROID_NDK"
echo "sherpa-onnx version: $SHERPA_ONNX_VERSION"

BUILD_BASE="${SCRIPT_DIR}/build"
mkdir -p "$BUILD_BASE"

SHERPA_ONNX_SRC="${BUILD_BASE}/sherpa-onnx-${SHERPA_ONNX_VERSION}"
if [ ! -d "$SHERPA_ONNX_SRC" ]; then
    echo "Downloading sherpa-onnx v${SHERPA_ONNX_VERSION}..."
    TAR_FILE="${BUILD_BASE}/sherpa-onnx-${SHERPA_ONNX_VERSION}.tar.gz"
    curl -L -o "$TAR_FILE" "$SHERPA_ONNX_TAR_URL"
    tar -xzf "$TAR_FILE" -C "$BUILD_BASE"
    rm "$TAR_FILE"
    echo "Downloaded and extracted to $SHERPA_ONNX_SRC"
fi

# 构建目标：仅 arm64-v8a（主流设备）
ABI="arm64-v8a"
echo ""
echo "=== Building for $ABI ==="

ONNX_LIB_DIR="${JNI_DIR}/onnxruntime/lib/${ABI}"
ONNX_INCLUDE_DIR="${JNI_DIR}/onnxruntime/include"

if [ ! -f "$ONNX_LIB_DIR/libonnxruntime.so" ]; then
    echo "ERROR: onnxruntime not found at $ONNX_LIB_DIR"
    echo "Run ./gradlew downloadOnnx first"
    exit 1
fi

BUILD_DIR="${BUILD_BASE}/sherpa-onnx-build-${ABI}"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

export SHERPA_ONNXRUNTIME_LIB_DIR="$ONNX_LIB_DIR"
export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ONNX_INCLUDE_DIR"

# 最小化构建：只保留流式 ASR
cmake -S "$SHERPA_ONNX_SRC" -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DCMAKE_BUILD_TYPE=MinSizeRel \
    -DBUILD_SHARED_LIBS=ON \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-21 \
    \
    -DSHERPA_ONNX_ENABLE_JNI=ON \
    -DSHERPA_ONNX_ENABLE_BINARY=OFF \
    -DSHERPA_ONNX_ENABLE_C_API=OFF \
    -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF \
    -DSHERPA_ONNX_ENABLE_PYTHON=OFF \
    -DSHERPA_ONNX_ENABLE_TESTS=OFF \
    -DSHERPA_ONNX_ENABLE_CHECK=OFF \
    -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
    \
    -DSHERPA_ONNX_ENABLE_TTS=OFF \
    -DSHERPA_ONNX_ENABLE_OFFLINE_TTS=OFF \
    -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF \
    -DSHERPA_ONNX_ENABLE_AUDIO_TAGGING=OFF \
    -DSHERPA_ONNX_ENABLE_KEYWORD_SPOTTER=OFF \
    -DSHERPA_ONNX_ENABLE_VAD=OFF \
    -DSHERPA_ONNX_ENABLE_ONLINE_PUNCTUATION=OFF \
    -DSHERPA_ONNX_ENABLE_OFFLINE_PUNCTUATION=OFF \
    -DSHERPA_ONNX_ENABLE_OFFLINE_RECOGNIZER=OFF \
    -DSHERPA_ONNX_ENABLE_OFFLINE_SPEECH_DENOISER=OFF \
    -DSHERPA_ONNX_ENABLE_ONLINE_SPEECH_DENOISER=OFF \
    -DSHERPA_ONNX_ENABLE_SPEAKER_ID=OFF \
    -DSHERPA_ONNX_ENABLE_SLI=OFF \
    \
    -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF \
    -DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=ON \
    \
    -DCMAKE_INSTALL_PREFIX="$BUILD_DIR/install"

cmake --build "$BUILD_DIR" -j$(sysctl -n hw.ncpu) --target install

# Strip 调试符号
JNI_SO="${BUILD_DIR}/install/lib/libsherpa-onnx-jni.so"
if [ -f "$JNI_SO" ]; then
    echo "Stripping debug symbols..."
    "$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip" --strip-all "$JNI_SO"
fi

JNI_DEST_DIR="${JNI_LIBS_DIR}/${ABI}"
mkdir -p "$JNI_DEST_DIR"
cp -fv "$JNI_SO" "$JNI_DEST_DIR/"

SIZE=$(ls -lh "$JNI_DEST_DIR/libsherpa-onnx-jni.so" | awk '{print $5}')
echo ""
echo "=== Build complete ==="
echo "Output: $JNI_DEST_DIR/libsherpa-onnx-jni.so"
echo "Size: $SIZE"
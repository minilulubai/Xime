# 构建 sherpa-onnx JNI 库（仅流式 ASR 功能）
# 来源: https://github.com/k2-fsa/sherpa-onnx
# 许可证: Apache License 2.0

$ErrorActionPreference = "Stop"

$SCRIPT_DIR = Split-Path -Parent $PSCommandPath
$SHERPA_ONNX_VERSION = "1.13.0"
$SHERPA_ONNX_TAR_URL = "https://github.com/k2-fsa/sherpa-onnx/archive/refs/tags/v${SHERPA_ONNX_VERSION}.tar.gz"

$APP_DIR = Join-Path $SCRIPT_DIR "app"
$JNI_DIR = Join-Path $APP_DIR "src/main/jni"
$JNI_LIBS_DIR = Join-Path $APP_DIR "src/main/jniLibs"

if (-not $env:ANDROID_NDK) {
    if ($env:ANDROID_SDK_ROOT) {
        $sdkRoot = $env:ANDROID_SDK_ROOT
    } else {
        $sdkRoot = Join-Path $env:LOCALAPPDATA "Android/Sdk"
    }
    $ndkDir = Join-Path $sdkRoot "ndk"
    if (Test-Path $ndkDir) {
        $env:ANDROID_NDK = Get-ChildItem $ndkDir -Directory |
            Sort-Object Name -Descending |
            Select-Object -First 1 -ExpandProperty FullName
    }
}

if (-not $env:ANDROID_NDK -or -not (Test-Path $env:ANDROID_NDK)) {
    Write-Error "ANDROID_NDK not found`nPlease set ANDROID_NDK environment variable"
    exit 1
}

Write-Host "=== Building sherpa-onnx JNI (minimal ASR only) ==="
Write-Host "ANDROID_NDK: $env:ANDROID_NDK"
Write-Host "sherpa-onnx version: $SHERPA_ONNX_VERSION"

$BUILD_BASE = Join-Path $SCRIPT_DIR "build"
New-Item -ItemType Directory -Force -Path $BUILD_BASE | Out-Null

$SHERPA_ONNX_SRC = Join-Path $BUILD_BASE "sherpa-onnx-${SHERPA_ONNX_VERSION}"
if (-not (Test-Path $SHERPA_ONNX_SRC)) {
    Write-Host "Downloading sherpa-onnx v${SHERPA_ONNX_VERSION}..."
    $TAR_FILE = Join-Path $BUILD_BASE "sherpa-onnx-${SHERPA_ONNX_VERSION}.tar.gz"
    Invoke-WebRequest -Uri $SHERPA_ONNX_TAR_URL -OutFile $TAR_FILE
    tar -xzf $TAR_FILE -C $BUILD_BASE
    Remove-Item $TAR_FILE
    Write-Host "Downloaded and extracted to $SHERPA_ONNX_SRC"
}

$ABI = "arm64-v8a"
Write-Host "`n=== Building for $ABI ==="

$ONNX_LIB_DIR = Join-Path $JNI_DIR "onnxruntime/lib/$ABI"
$ONNX_INCLUDE_DIR = Join-Path $JNI_DIR "onnxruntime/include"

$ONNX_SO = Join-Path $ONNX_LIB_DIR "libonnxruntime.so"
if (-not (Test-Path $ONNX_SO)) {
    Write-Error "onnxruntime not found at $ONNX_LIB_DIR`nRun ./gradlew downloadOnnx first"
    exit 1
}

$BUILD_DIR = Join-Path $BUILD_BASE "sherpa-onnx-build-${ABI}"
if (Test-Path $BUILD_DIR) { Remove-Item -Recurse -Force $BUILD_DIR }
New-Item -ItemType Directory -Force -Path $BUILD_DIR | Out-Null

$env:SHERPA_ONNXRUNTIME_LIB_DIR = $ONNX_LIB_DIR -replace '\\', '/'
$env:SHERPA_ONNXRUNTIME_INCLUDE_DIR = $ONNX_INCLUDE_DIR -replace '\\', '/'

cmake -S $SHERPA_ONNX_SRC -B $BUILD_DIR -G Ninja `
    -DCMAKE_TOOLCHAIN_FILE="$env:ANDROID_NDK/build/cmake/android.toolchain.cmake" `
    -DCMAKE_BUILD_TYPE=MinSizeRel `
    -DBUILD_SHARED_LIBS=ON `
    -DANDROID_ABI="$ABI" `
    -DANDROID_PLATFORM=android-21 `
    `
    -DSHERPA_ONNX_ENABLE_JNI=ON `
    -DSHERPA_ONNX_ENABLE_BINARY=OFF `
    -DSHERPA_ONNX_ENABLE_C_API=OFF `
    -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF `
    -DSHERPA_ONNX_ENABLE_PYTHON=OFF `
    -DSHERPA_ONNX_ENABLE_TESTS=OFF `
    -DSHERPA_ONNX_ENABLE_CHECK=OFF `
    -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF `
    `
    -DSHERPA_ONNX_ENABLE_TTS=OFF `
    -DSHERPA_ONNX_ENABLE_OFFLINE_TTS=OFF `
    -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF `
    -DSHERPA_ONNX_ENABLE_AUDIO_TAGGING=OFF `
    -DSHERPA_ONNX_ENABLE_KEYWORD_SPOTTER=OFF `
    -DSHERPA_ONNX_ENABLE_VAD=OFF `
    -DSHERPA_ONNX_ENABLE_ONLINE_PUNCTUATION=OFF `
    -DSHERPA_ONNX_ENABLE_OFFLINE_PUNCTUATION=OFF `
    -DSHERPA_ONNX_ENABLE_OFFLINE_RECOGNIZER=OFF `
    -DSHERPA_ONNX_ENABLE_OFFLINE_SPEECH_DENOISER=OFF `
    -DSHERPA_ONNX_ENABLE_ONLINE_SPEECH_DENOISER=OFF `
    -DSHERPA_ONNX_ENABLE_SPEAKER_ID=OFF `
    -DSHERPA_ONNX_ENABLE_SLI=OFF `
    `
    -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF `
    -DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=ON `
    `
    -DCMAKE_INSTALL_PREFIX="$($BUILD_DIR -replace '\\', '/')/install"

cmake --build $BUILD_DIR -j $env:NUMBER_OF_PROCESSORS --target install

$JNI_SO = Join-Path $BUILD_DIR "install/lib/libsherpa-onnx-jni.so"
if (Test-Path $JNI_SO) {
    Write-Host "Stripping debug symbols..."
    $STRIP_TOOL = Join-Path $env:ANDROID_NDK "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe"
    & $STRIP_TOOL --strip-all $JNI_SO
}

$JNI_DEST_DIR = Join-Path $JNI_LIBS_DIR $ABI
New-Item -ItemType Directory -Force -Path $JNI_DEST_DIR | Out-Null
Copy-Item -Path $JNI_SO -Destination $JNI_DEST_DIR/ -Force

$SIZE = (Get-Item "$JNI_DEST_DIR/libsherpa-onnx-jni.so").Length
Write-Host "`n=== Build complete ==="
Write-Host "Output: $JNI_DEST_DIR/libsherpa-onnx-jni.so"
Write-Host "Size: $SIZE bytes"

#!/usr/bin/env bash
# Build the Android (arm64) APK of Daytona USA on a Linux x64 PC.
#
# One command, from a fresh clone to an installable APK:
#
#   scripts/build_android.sh <path to your XBLA package>       # first time
#   scripts/build_android.sh                                    # rebuild
#   scripts/build_android.sh --install                          # + install over USB (adb)
#
# Steps (each one is skipped when its output already exists):
#   1. submodules
#   2. Android SDK + NDK, downloaded into android/.sdk/ unless ANDROID_HOME is set
#   3. the rexglue CLI for this PC (the code generator)
#   4. your package -> extracted/ (game data) + assets/default.xex (codegen input)
#   5. scripts/regen.sh -> generated/ (the game's code, recompiled to C++)
#   6. CMake + NDK -> libmain.so, librexruntime.so, libSDL3.so, GPU plugins
#   7. Gradle -> android/app/build/outputs/apk/release/daytona-android.apk
#   8. with --install: adb install, then push extracted/ to the device
#
# The APK contains the game's program code (recompiled), never its data. It is
# for your own device: do not share it.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

NDK_VERSION="r30"
NDK_REVISION="30.0.16248370"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-16111833_latest.zip"
PLATFORM="android-35"
BUILD_TOOLS="35.0.0"
PACKAGE_ID="org.daytonarecomp.app"
DEVICE_DIR="/sdcard/Android/data/${PACKAGE_ID}/files"

INSTALL=0
PACKAGE=""
JOBS="${JOBS:-$(nproc)}"
for arg in "$@"; do
  case "$arg" in
    --install) INSTALL=1 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) PACKAGE="$arg" ;;
  esac
done

say() { printf '\n\033[1;34m[android]\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31m[android] %s\033[0m\n' "$*" >&2; exit 1; }

# --- 0. tools -----------------------------------------------------------------
missing=()
for tool in git cmake ninja python3 java curl unzip pkg-config; do
  command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
done
if ((${#missing[@]})); then
  die "missing tools: ${missing[*]}
  Debian/Ubuntu: sudo apt install git cmake ninja-build python3 openjdk-17-jdk curl unzip pkg-config libx11-xcb-dev
  Fedora:        sudo dnf install git cmake ninja-build python3 java-17-openjdk-devel curl unzip pkgconf libX11-devel
  Arch:          sudo pacman -S git cmake ninja python jdk17-openjdk curl unzip pkgconf libx11"
fi
java_major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
[ "${java_major:-0}" -ge 17 ] || die "Java 17 or newer is needed for Gradle (found: ${java_major:-none})"

# --- 1. submodules ------------------------------------------------------------
if [ ! -f thirdparty/rexglue-sdk/CMakeLists.txt ] || [ ! -f thirdparty/plume/CMakeLists.txt ] \
   || [ ! -f thirdparty/rexglue-sdk/thirdparty/sdl3/CMakeLists.txt ]; then
  say "fetching submodules (first time: a few minutes)"
  git -c core.symlinks=true submodule update --init --recursive
fi

# --- 2. Android SDK + NDK -------------------------------------------------------
if [ -z "${ANDROID_HOME:-}" ]; then
  ANDROID_HOME="$ROOT/android/.sdk"
fi
export ANDROID_HOME
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  say "downloading the Android command-line tools into $ANDROID_HOME"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmp=$(mktemp -d)
  curl -fL --progress-bar -o "$tmp/clt.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  unzip -q "$tmp/clt.zip" -d "$tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi
NDK_DIR="$ANDROID_HOME/ndk/$NDK_REVISION"
if [ ! -f "$NDK_DIR/build/cmake/android.toolchain.cmake" ] || [ ! -d "$ANDROID_HOME/platforms/$PLATFORM" ]; then
  say "installing NDK $NDK_VERSION, $PLATFORM and build-tools (about 2 GB, once)"
  yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
  "$SDKMANAGER" --sdk_root="$ANDROID_HOME" "ndk;$NDK_REVISION" "platforms;$PLATFORM" \
    "build-tools;$BUILD_TOOLS" "platform-tools"
fi
[ -f "$NDK_DIR/build/cmake/android.toolchain.cmake" ] || die "NDK not found at $NDK_DIR"
ADB="$ANDROID_HOME/platform-tools/adb"

# --- 3. rexglue CLI (host) ------------------------------------------------------
SDK="$ROOT/thirdparty/rexglue-sdk"
REXGLUE=""
for cand in "$SDK/out/linux-amd64/Release/rexglue" "$SDK/out/linux-amd64/rexglue"; do
  [ -x "$cand" ] && REXGLUE="$cand" && break
done
if [ -z "$REXGLUE" ]; then
  say "building the rexglue code generator for this PC"
  # Compiled with the NDK's clang (21), which also targets this PC: the SDK
  # needs clang 20+ (std::expected), newer than most distributions ship.
  # Only the CLI is built, so SDL's desktop video backends are left out.
  HOST_BIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
  cmake --preset linux-amd64 -S "$SDK" -B "$SDK/out/build/linux-amd64" \
    -DCMAKE_C_COMPILER="$HOST_BIN/clang" -DCMAKE_CXX_COMPILER="$HOST_BIN/clang++" \
    -DSDL_X11=OFF -DSDL_WAYLAND=OFF -DSDL_KMSDRM=OFF
  cmake --build "$SDK/out/build/linux-amd64" --config Release --target rexglue -j "$JOBS"
  REXGLUE="$SDK/out/linux-amd64/Release/rexglue"
fi
export REXGLUE

# --- 4. game files --------------------------------------------------------------
if [ ! -f assets/default.xex ] || [ ! -d extracted ]; then
  if [ -n "$PACKAGE" ]; then
    say "extracting your package"
    python3 scripts/extract_game.py "$PACKAGE"
  elif [ -n "$(ls -A game 2>/dev/null)" ]; then
    say "extracting the package in game/"
    python3 scripts/extract_game.py
  else
    die "no game files yet. Run: scripts/build_android.sh <path to your Daytona USA XBLA package>
  (the LIVE/STFS file copied off your own Xbox 360: no extension, long hex name)"
  fi
fi

# --- 5. codegen -----------------------------------------------------------------
if [ ! -f generated/default/sources.cmake ] || [ assets/default.xex -nt generated/default/sources.cmake ]; then
  say "recompiling the game's code (codegen)"
  bash scripts/regen.sh
fi
if [ ! -f generated/rexglue.cmake ]; then
  python3 scripts/render_rexglue_cmake.py "$ROOT/thirdparty/rexglue-sdk" generated/rexglue.cmake
fi

# --- 6. native build ------------------------------------------------------------
BUILD="$ROOT/out/build/android-arm64-release"
say "building the native libraries for arm64 (first time: long, the game is a big C++ file set)"
cmake -S . -B "$BUILD" -G Ninja -Wno-dev \
  -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release -DREXSDK_DIR="$SDK"
cmake --build "$BUILD" --target daytona rexgpu-plume -j "$JOBS"

say "staging the libraries into the APK"
JNI="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
rm -rf "$JNI"
mkdir -p "$JNI"
SDK_OUT="$SDK/out/android-arm64"
cp "$BUILD/libmain.so" "$BUILD/librexgpu-plume.so" "$JNI/"
for lib in librexruntime.so libSDL3.so libTracyClient.so librexgpu-xenos.so; do
  cp "$SDK_OUT/$lib" "$JNI/"
done
cp "$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$JNI/"
STRIP="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
"$STRIP" --strip-unneeded "$JNI"/*.so

# --- 7. APK ---------------------------------------------------------------------
say "packaging the APK (Gradle; first time downloads Gradle itself)"
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
(cd android && ./gradlew --no-daemon assembleRelease)
APK_SRC="android/app/build/outputs/apk/release/app-release.apk"
APK="android/app/build/outputs/apk/release/daytona-android.apk"
cp "$APK_SRC" "$APK"
say "APK ready: $ROOT/$APK"

# --- 8. install -----------------------------------------------------------------
if ((INSTALL)); then
  "$ADB" get-state >/dev/null 2>&1 || die "no device over adb. On the Retroid: Settings > About > tap Build number 7x,
  then Developer options > USB debugging ON, plug the USB cable and accept the prompt."
  say "installing on the device"
  "$ADB" install -r "$APK"
  # Starting the app once creates its files folder with the right owner.
  "$ADB" shell am start -n "$PACKAGE_ID/.DaytonaActivity" >/dev/null
  sleep 3
  "$ADB" shell am force-stop "$PACKAGE_ID"
  if ! "$ADB" shell "test -f $DEVICE_DIR/assets/default.xex"; then
    say "copying the game data to the device (about 240 MB)"
    "$ADB" shell mkdir -p "$DEVICE_DIR/assets"
    "$ADB" push extracted/. "$DEVICE_DIR/assets/"
  fi
  if ! "$ADB" shell "test -f $DEVICE_DIR/daytona.ini"; then
    "$ADB" push daytona.ini "$DEVICE_DIR/daytona.ini"
  fi
  say "done: start \"Daytona USA\" on the device"
fi

---
name: rexglue-android-port
description: Port a ReXGlue-SDK static recompilation of an Xbox 360 game (rexglue codegen + plume Vulkan renderer + SDL3) to Android arm64 and ship a sideloadable APK for a handheld (Retroid Pocket, Ayn Odin, Snapdragon/Adreno phones). Use when asked to build, port, debug or improve an Android build of a rexglue/plume recomp (Daytona USA or another title), when an APK of such a port crashes on device, renders nothing, or has wrong controls, or when reading a crash report from one. Covers the toolchain, the Bionic/SDL3/Vulkan/Adreno pitfalls already solved, the device test loop for a user who only has the device, and legal limits on game data.
---

# Porting a ReXGlue recomp to Android

Reference implementation (all solved, reuse it instead of rederiving):

| Repo (fork) | Branch | What it holds |
|---|---|---|
| `Manadthan/rexglue-sdk` | `android/arm64` | SDK runtime for Android: platform, fibers, SDL_main, surface, input diag, fixes |
| `Manadthan/plume` | `android` | Vulkan RHI: null pipeline on failure, device-caps log, surface-format log |
| `Manadthan/daytona_recomp` | `android` | Game: `android/` Gradle app, `scripts/build_android.sh`, `src/dt_spirv_int64.*`, `src/dt_android_surface.cpp`, `ANDROID.md` |

For a new game: point its `.gitmodules` at those two forks/branches, then copy
`android/`, `scripts/build_android.sh`, `scripts/render_rexglue_cmake.py`,
`src/dt_spirv_int64.*`, `src/dt_android_surface.cpp` and the Android branches
of `CMakeLists.txt`, `src/main.cpp`, `src/dt_plume.cpp` from daytona_recomp,
renaming `dt_`/`daytona`/`org.daytonarecomp.app`. Read `references/pitfalls.md`
before changing anything low-level: every item there cost a device round-trip.

## Ground rules

- **Game data never goes in a repo, an APK pushed anywhere, or a public place.**
  The user supplies their own package (XBLA LIVE/STFS file or disc dump). Refuse
  leaked dev/prototype builds; check the package is a retail one. The APK holds
  recompiled game code: it is for the user's own device only, say so.
- Anything derived from the package (e.g. the launcher icon from `game.png`) is
  generated at build time into `build/`, never committed.
- No PR unless asked. Never commit temporary submodule symlinks or version stamps
  (`daytona_manifest.toml`).

## Toolchain (cloud container, no device)

- Needs hosts `dl.google.com` and `maven.google.com` (Android SDK/NDK, Gradle
  deps). Ask for them through the environment tools if blocked.
- NDK r30 (clang 21). The host-side `rexglue` codegen CLI needs clang 20+ for
  `std::expected`: build it **with the NDK's clang** targeting the host, with
  SDL X11/Wayland/KMSDRM off.
- Codegen needs `default.xex` from the user's package; it does not write
  `generated/rexglue.cmake`, render it from the template.
- Submodules are empty gitlinks in the game repo: for a local build, replace
  `thirdparty/rexglue-sdk` and `thirdparty/plume` dirs by symlinks to the SDK
  and plume checkouts, build, then restore empty dirs before committing; bump the
  game's gitlink with `git update-index --cacheinfo 160000,<sha>,thirdparty/rexglue-sdk`.
- Full build: `ANDROID_HOME=/opt/android/sdk scripts/build_android.sh` (skips
  finished steps). Gradle only: `cd android && ./gradlew --no-daemon -q assembleRelease`.
- Bump `versionCode`/`versionName` every APK and name the sent file
  `<game>-android-vN.apk`: users reinstall old files by mistake, and the crash
  report prints the version.

## App shape that works

- Game = `add_library(<game> SHARED)` with `OUTPUT_NAME main` → `libmain.so`;
  SDL3 **shared** (`libSDL3.so`); `extern "C" SDL_main`; `getLibraries()` =
  `{"SDL3","main"}`; SDL Java sources taken from the exact SDL submodule.
- arm64-v8a only, minSdk 29, `useLegacyPackaging true` + `extractNativeLibs`
  (runtime dlopens `librexgpu-*.so` from disk), debug-signed release.
- `SetupActivity` (launcher) imports the game with the system file picker
  (`ACTION_OPEN_DOCUMENT`): accepts the LIVE package, a zip of it, or a zip of
  extracted files; extracts STFS in pure Java (`StfsExtractor`, verified
  byte-identical to `extract_game.py`); zip-slip check. No PC or adb needed.
- Game activity: `singleInstance`, `sensorLandscape`, not exported;
  `SDL_ENV.SDL_ANDROID_TRAP_BACK_BUTTON=1`.
- Data in `getExternalFilesDir()` (`REX_ANDROID_APP_DIR`): ini, cache, assets, logs.

## Device test loop (user has only the handheld, often answers from a phone)

- Every session ends with a report saved to `Download/<game>-journal-<date>.txt`
  via MediaStore (no permission): version, device, `ApplicationExitInfo`, end of
  `last_run.log` (stdio teed by SDL_main), the newest game log tail, a
  "Manette" section (all `SDL input diag` lines), and `logcat -d`.
  Show the report screen only after a real crash (CRASH, CRASH_NATIVE, ANR,
  INITIALIZATION_FAILURE).
- Unclaimed SIGSEGV/SIGILL log `FATAL: signal … pc (lib+off, sym) … lr`.
- Ask for one precise manipulation per build, and say where it can be done
  (controller tests work on the boot logos; no need to start a race).
- Answer briefly, in the user's language, with the APK sent as a file.

## Diagnosis cheat-sheet (symptom → cause → fix, details in pitfalls.md)

| Symptom on device | Cause | Fix |
|---|---|---|
| "x11 not available" | SDL video hint forced to x11 | guard hint with `!REX_PLATFORM_ANDROID` |
| crash in `vkCmdBindPipeline`, pipelines fail | Adreno `shaderInt64=0`; plume stored invalid pipeline | null pipeline on failure + SPIR-V int64→uvec2 lowering |
| "No compatible surface formats" | Android offers only R8G8B8A8 | RGBA swapchain/blit/imgui format |
| crash in `vkCreateFramebuffer` after Home | swapchain kept on a destroyed surface | release swapchain synchronously in `surfaceDestroyed`; skip rebuild when resize fails/empty |
| random Scudo double free in `set_name` | thread-name race | lock thread name; copy under lock |
| sticks/analog triggers do nothing, buttons OK | `SDL_StartTextInput` focuses SDLDummyEdit, which eats generic-motion events | don't start text input on Android; L2/R2 keys (SDL buttons 15/16) as trigger fallback |

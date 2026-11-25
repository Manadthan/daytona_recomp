# Pitfalls solved in the Daytona USA Android port

Each item: what broke, why, where the fix lives (forks named in SKILL.md).

## Build / Bionic

- **No `x11-xcb` / ALSA / Pulse on Android.** Add `ANDROID` branches in the SDK
  CMake (`REX_PLATFORM "android-arm64"`, Android UI sources, link `android log dl`).
- **No `ucontext` (`swapcontext`)** → `src/core/fiber_android.cpp`: AArch64 asm
  `rex_fiber_switch` saving x19–x30, d8–d15 (176-byte frame) + `rex_fiber_start`;
  `fiber.h` Android variant holds `void* sp_`. Test under qemu-aarch64.
- **No robust mutexes** in Bionic → skip `PTHREAD_MUTEX_ROBUST` on Android.
- **No `std::chrono::clock_cast`** in NDK libc++ → reuse the shim under `__ANDROID__`.
- **`GetWindowedAppCreator` undefined** → `XE_UI_WINDOWED_APPS_IN_LIBRARY` defaults to 0.
- **Two static SDL copies** (SDK + app) → SDL shared on Android.
- `GetExecutablePath` → `dladdr` on Android.
- **Page size must be 4 KiB** (`REX_PHYS_HOST_OFFSET` is 0 on Linux/Android):
  SDL_main checks `getpagesize()` and says so instead of crashing later.
- SDL_main sets `HOME`, `XDG_DATA_HOME`, `REX_ANDROID_APP_DIR` to
  `SDL_GetAndroidExternalStoragePath()` and pipes stdio to logcat + `last_run.log`.
- Force `fullscreen=true` on Android; raise log level to info after the ini is applied.

## Vulkan / Adreno (740, driver 0676.x)

- `shaderInt64 = 0`. The game's SPIR-V uses 64-bit ints only for buffer device
  addresses. `dt_spirv_int64.cpp` lowers them after smolv decode: retype u64 as
  uvec2; OpConstant→ConstantComposite; IAdd/ISub with carry/borrow; 64-bit
  ShiftLeftLogical via selects; UConvert; IEqual/INotEqual→All/Any; Bitcast
  uvec2→pointer stays legal; drop the Int64 capability; refuse unknown 64-bit ops
  (only inspect id operands — an OpExtInst literal gave a false positive).
  Validate every shader with `spirv-val --target-env vulkan1.3`.
- plume must return nullptr when `vkCreateGraphicsPipelines` fails; binding a
  VK_NULL_HANDLE pipeline crashes inside the driver.
- Surfaces only offer `R8G8B8A8_UNORM/SRGB`: use it for swapchain, blit and imgui.
- Log device caps once (`shaderInt64=… bufferDeviceAddress=…`): first thing to
  read in any report.

## Android lifecycle (Home / resume)

- `DaytonaSurface extends SDLSurface`: call `nativeSurfaceReady(Surface)` after
  `super.surfaceChanged`, `nativeSurfaceLost()` **before** `super.surfaceDestroyed`.
- `AndroidSurfaceLost()`: lock the renderer, wait both fences, drop framebuffers,
  swapchain, semaphores, release the ANativeWindow; set a paused flag so
  `EnsureWindow` returns false until a new surface arrives.
- `EnsureSwapchainSized`: if `resize()` fails or the swapchain is empty, return
  false — never rebuild framebuffers on an abandoned BufferQueue.
- Known limit: the game keeps simulating in background (no auto-pause yet).

## Threads

- Scudo "invalid chunk state / race on chunk header" in
  `PosixThread::set_name`: name written by the guest thread while the start
  lambda reads it. Fix: `name()` returns a copy under a mutex, `set_name` locks;
  XThread start copies `thread_name_` under `thread_lock_`.

## Input (SDL3 on Android)

- Retroid Pocket 6: "Retroid Pocket Controller" VID 0x2022 PID 0x3001, 6 axes,
  19 buttons, SDL mapping already correct (leftx a0 … righttrigger a5).
- **Root cause of "stick moves the camera, triggers dead":** the SDK called
  `SDL_StartTextInput` at window creation. On Android that focuses
  `SDLDummyEdit`, so joystick generic-motion events never reach `SDLSurface`.
  Buttons still work (key events), the D-pad (often bound to camera/view) too.
  Symptom in logcat: `Ignoring showSoftInput() as view=…SDLDummyEdit`.
- Android also sends L2/R2 as keys → SDL raw buttons 15 (MISC1) and 16. Use them
  as full-travel triggers until a real trigger axis is seen.
- Diagnostics kept in `sdl_input_driver.cpp`: device line with the mapping,
  half-travel crossings of raw and gamepad axes, raw button downs (bounded).
- Overrides possible via the `hid_mappings_file` cvar (gamecontrollerdb format).

## Packaging / UX

- Report saved to Downloads after any session that ended without the activity
  finishing; `ApplicationExitInfo` gives the reason.
- Launcher icon: `extracted/game.png` (64×64 tile from the user's package)
  upscaled to 192 px by Gradle (ImageIO bicubic) into `build/generated/gameicon/res`,
  selected through a `${appIcon}` manifest placeholder; falls back to the generic
  drawable when the game is not extracted.

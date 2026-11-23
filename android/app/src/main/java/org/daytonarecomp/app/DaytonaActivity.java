package org.daytonarecomp.app;

import android.os.Bundle;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;

/**
 * SDL3 activity hosting the recompiled game (libmain.so, SDL_main).
 *
 * Game data lives in the app's external files folder:
 *   /sdcard/Android/data/org.daytonarecomp.app/files/assets/   (default.xex + the rest)
 *   /sdcard/Android/data/org.daytonarecomp.app/files/daytona.ini (optional)
 * The native entry point resolves the same folder through SDL and passes it
 * to the runtime; this class only makes sure it exists so it shows up over USB.
 */
public class DaytonaActivity extends SDLActivity {
    private static final String TAG = "Daytona";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        File files = getExternalFilesDir(null);
        if (files != null) {
            File assets = new File(files, "assets");
            if (!assets.exists() && !assets.mkdirs()) {
                Log.w(TAG, "could not create " + assets);
            }
            Log.i(TAG, "game data folder: " + assets);
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected String[] getLibraries() {
        // libSDL3.so first (SDLActivity's JNI half), then the game. The game's
        // other dependencies (librexruntime.so, libc++_shared.so, ...) are
        // loaded by the dynamic linker from the same folder.
        return new String[] { "SDL3", "main" };
    }
}

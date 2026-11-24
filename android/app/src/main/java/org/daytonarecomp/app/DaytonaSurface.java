package org.daytonarecomp.app;

import android.content.Context;
import android.view.SurfaceHolder;

import org.libsdl.app.SDLSurface;

/**
 * SDL's surface, plus the two calls the renderer needs around the Android
 * lifecycle. surfaceDestroyed runs before Android takes the surface away
 * (Home, switching apps, screen off): the renderer releases its swapchain
 * first, synchronously, or presenting into the dead surface crashes the
 * driver. On the way back, the new surface is handed over and the swapchain
 * is rebuilt on the next frame.
 */
public class DaytonaSurface extends SDLSurface {
    private static native void nativeSurfaceLost();
    private static native void nativeSurfaceReady(Object surface);

    public DaytonaSurface(Context context) {
        super(context);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        super.surfaceChanged(holder, format, width, height);
        try {
            nativeSurfaceReady(holder.getSurface());
        } catch (UnsatisfiedLinkError e) {
            // libmain not loaded (failed start): nothing renders anyway.
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        try {
            nativeSurfaceLost();
        } catch (UnsatisfiedLinkError e) {
            // see above
        }
        super.surfaceDestroyed(holder);
    }
}

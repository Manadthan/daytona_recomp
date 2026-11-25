// JNI entry points for org.daytonarecomp.app.DaytonaSurface: the activity's
// surface going away (Home, app switch, screen off) and coming back. They run
// on the Java UI thread, synchronously inside SurfaceHolder.Callback, which is
// the only window Android gives to stop presenting into a surface before it is
// destroyed.
#if defined(__ANDROID__)

#include <android/native_window_jni.h>
#include <jni.h>

#include "dt_plume.h"

extern "C" {

JNIEXPORT void JNICALL Java_org_daytonarecomp_app_DaytonaSurface_nativeSurfaceLost(JNIEnv*, jclass) {
    dt_plume::AndroidSurfaceLost();
}

JNIEXPORT void JNICALL Java_org_daytonarecomp_app_DaytonaSurface_nativeSurfaceReady(JNIEnv* env, jclass,
                                                                                   jobject surface) {
    if (surface == nullptr) return;
    // One reference, handed to (and released by) dt_plume.
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window != nullptr) dt_plume::AndroidSurfaceReady(window);
}

JNIEXPORT void JNICALL Java_org_daytonarecomp_app_DaytonaActivity_nativeAppQuitting(JNIEnv*, jclass) {
    dt_plume::AndroidAppQuitting();
}

}  // extern "C"

#endif  // __ANDROID__

package jp.sakaguchi.dancerecenter;

import android.content.Context;

/** JNI contract shared by both proof buttons. No full-video export. */
public final class GyroflowBridge {
    static { System.loadLibrary("dance_gyroflow_jni"); }
    private GyroflowBridge() {}
    public static native void nativeInit(Context context);
    public static native String nativePrepareStabilization(int fd, long durationMs,
            int width, int height, int fpsX1000);
    public static native String nativeStabilizeFrame(int fd, long durationMs,
            int width, int height, int fpsX1000, long timestampUs,
            byte[] rgbaBefore, byte[] rgbaAfter);
}

package com.rokid.stabledemo;

/** Package-private JNI API. Its handle is owned exclusively by the sensor thread. */
final class FusionBridge {
    static { System.loadLibrary("rokid_fusion"); }
    private FusionBridge() {}
    static native long create(float sampleRate, float gyroRangeRad);
    static native void update(long handle, float gx, float gy, float gz,
            float ax, float ay, float az, float dt, float[] result);
    static native void destroy(long handle);
}

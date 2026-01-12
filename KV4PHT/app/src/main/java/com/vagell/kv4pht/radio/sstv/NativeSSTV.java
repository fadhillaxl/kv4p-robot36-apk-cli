package com.vagell.kv4pht.radio.sstv;

import android.graphics.Bitmap;

public class NativeSSTV {
    static {
        try { System.loadLibrary("native-lib"); } catch (Throwable ignored) {}
    }

    public static native boolean isNativeAvailable();
    public static native byte[] encodeRobot36ToWav(Bitmap bmp);
    public static native Bitmap decodeRobot36ToBitmap(float[] samples, int sampleRate);

    public static native void startRobot36Decoder(int sampleRate);
    public static native void feedRobot36Samples(float[] samples, int sampleRate);
    public static native Robot36Progress getRobot36Progress();
    public static native void stopRobot36Decoder();
}
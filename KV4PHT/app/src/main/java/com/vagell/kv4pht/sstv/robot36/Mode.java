package com.vagell.kv4pht.sstv.robot36;

import android.graphics.Bitmap;

public interface Mode {
    String getName();

    int getVISCode();

    int getWidth();

    int getHeight();

    int getFirstPixelSampleIndex();

    int getFirstSyncPulseIndex();

    int getScanLineSamples();

    Bitmap postProcessScopeImage(Bitmap bmp);

    void resetState();

    boolean decodeScanLine(PixelBuffer pixelBuffer, float[] scratchBuffer, float[] scanLineBuffer, int scopeBufferWidth, int syncPulseIndex, int scanLineSamples, float frequencyOffset);
}
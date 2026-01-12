package com.vagell.kv4pht.radio.sstv;

import android.graphics.Bitmap;

public class Robot36Progress {
    public final int lineIndex;
    public final Bitmap bitmap;
    public final String state;
    public final boolean completed;

    public Robot36Progress(int lineIndex, Bitmap bitmap, String state, boolean completed) {
        this.lineIndex = lineIndex;
        this.bitmap = bitmap;
        this.state = state;
        this.completed = completed;
    }
}
package com.vagell.kv4pht.sstv.robot36;

public class PixelBuffer {
    public int[] pixels;
    public int width;
    public int height;
    public int line;

    public PixelBuffer(int width, int height) {
        this.width = width;
        this.height = height;
        this.line = 0;
        this.pixels = new int[width * height];
    }
}
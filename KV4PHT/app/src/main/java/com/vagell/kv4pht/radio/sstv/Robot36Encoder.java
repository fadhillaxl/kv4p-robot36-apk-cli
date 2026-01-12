package com.vagell.kv4pht.radio.sstv;

import android.graphics.Bitmap;

import com.vagell.kv4pht.radio.RadioAudioService;

import java.io.IOException;

public class Robot36Encoder {
    public interface FrameSink { void accept(float[] frame); }

    private final int sampleRate;
    private final int frameSize;
    private float phase = 0f;

    public Robot36Encoder() {
        this.sampleRate = RadioAudioService.AUDIO_SAMPLE_RATE;
        this.frameSize = RadioAudioService.OPUS_FRAME_SIZE;
    }

    public Robot36Encoder(int sampleRate, int frameSize) {
        this.sampleRate = sampleRate;
        this.frameSize = frameSize > 0 ? frameSize : RadioAudioService.OPUS_FRAME_SIZE;
    }

    private void tone(float freq, int samples, FrameSink sink) {
        float[] frame = new float[frameSize];
        int idx = 0;
        for (int i = 0; i < samples; i++) {
            phase += (float)(2.0 * Math.PI * freq / sampleRate);
            if (phase > 2.0f * Math.PI) phase -= 2.0f * (float)Math.PI;
            float s = (float)Math.sin(phase);
            frame[idx++] = s;
            if (idx == frameSize) {
                sink.accept(frame);
                frame = new float[frameSize];
                idx = 0;
            }
        }
        if (idx > 0) {
            sink.accept(frame);
        }
    }

    private void silenceMs(int ms, FrameSink sink) {
        int samples = ms * sampleRate / 1000;
        float[] frame = new float[frameSize];
        int idx = 0;
        for (int i = 0; i < samples; i++) {
            frame[idx++] = 0f;
            if (idx == frameSize) {
                sink.accept(frame);
                frame = new float[frameSize];
                idx = 0;
            }
        }
        if (idx > 0) sink.accept(frame);
    }

    private void visHeader(FrameSink sink) {
        tone(1900f, ms(300), sink);
        tone(1200f, ms(10), sink);
        tone(1900f, ms(300), sink);
        tone(1200f, ms(30), sink);
        int code7 = 0x28 & 0x7F;
        int ones = 0;
        for (int i = 0; i < 7; i++) {
            boolean bit = ((code7 >> i) & 1) == 1;
            if (bit) ones++;
            tone(bit ? 1100f : 1300f, ms(30), sink);
        }
        boolean parityEven = (ones % 2) == 0;
        tone(parityEven ? 1300f : 1100f, ms(30), sink);
        tone(1200f, ms(30), sink);
    }

    private int ms(int durationMs) {
        return durationMs * sampleRate / 1000;
    }

    private float mapFreq(int v) {
        return 1500f + (v / 255f) * 800f;
    }

    private int[] getPixels(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        return px;
    }

    private int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private int yFromRgb(int r, int g, int b) {
        return clamp((int)(0.299f * r + 0.587f * g + 0.114f * b));
    }

    private int cbFromRgb(int r, int g, int b, int y) {
        return clamp((int)(128 + 0.564f * (b - y)));
    }

    private int crFromRgb(int r, int g, int b, int y) {
        return clamp((int)(128 + 0.713f * (r - y)));
    }

    public void encode(Bitmap bmp, FrameSink sink) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] px = getPixels(bmp);
        visHeader(sink);
        int usableLines = Math.min(240, h);
        for (int line = 0; line < usableLines; line++) {
            tone(1200f, ms(9), sink);
            tone(1500f, ms(3), sink);
            int yTotal = ms(88);
            int yPer = yTotal / 320;
            int yRem = yTotal - yPer * 320;
            for (int x = 0; x < 320; x++) {
                int idx = line * w + x;
                int c = px[idx];
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                int y = yFromRgb(r, g, b);
                float f = mapFreq(y);
                int ns = yPer + (x < yRem ? 1 : 0);
                tone(f, ns, sink);
            }
            tone(1900f, ms(4), sink);
            tone(1500f, ms(3), sink);
            int cTotal = ms(44);
            int cPer = cTotal / 160;
            int cRem = cTotal - cPer * 160;
            for (int x = 0; x < 160; x++) {
                int x0 = x * 2;
                int idx0 = line * w + x0;
                int idx1 = line * w + Math.min(x0 + 1, w - 1);
                int c0 = px[idx0];
                int c1 = px[idx1];
                int r0 = (c0 >> 16) & 0xff;
                int g0 = (c0 >> 8) & 0xff;
                int b0 = c0 & 0xff;
                int r1 = (c1 >> 16) & 0xff;
                int g1 = (c1 >> 8) & 0xff;
                int b1 = c1 & 0xff;
                int y0 = yFromRgb(r0, g0, b0);
                int y1 = yFromRgb(r1, g1, b1);
                int cb = (cbFromRgb(r0, g0, b0, y0) + cbFromRgb(r1, g1, b1, y1)) / 2;
                int cr = (crFromRgb(r0, g0, b0, y0) + crFromRgb(r1, g1, b1, y1)) / 2;
                int chroma = (line % 2 == 0) ? cr : cb;
                float f = mapFreq(chroma);
                int ns = cPer + (x < cRem ? 1 : 0);
                tone(f, ns, sink);
            }
        }
        silenceMs(700, sink);
    }

    public int estimateTotalSamples(Bitmap bmp) {
        int total = 0;
        total += ms(300) + ms(10) + ms(300) + ms(30) + (8 * ms(30)) + ms(30);
        int lines = Math.min(240, bmp.getHeight());
        for (int line = 0; line < lines; line++) {
            total += ms(9) + ms(3) + ms(88) + ms(4) + ms(3) + ms(44);
        }
        total += ms(700);
        return total;
    }

    public void encodeToWavFile(Bitmap bmp, java.io.File file, java.util.function.IntConsumer onFrame) throws java.io.IOException {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw");
        raf.setLength(0);
        raf.write(new byte[]{'R','I','F','F'});
        writeLE(raf, 0);
        raf.write(new byte[]{'W','A','V','E'});
        raf.write(new byte[]{'f','m','t',' '});
        writeLE(raf, 16);
        writeLEShort(raf, (short)1);
        writeLEShort(raf, (short)1);
        writeLE(raf, sampleRate);
        writeLE(raf, sampleRate * 2);
        writeLEShort(raf, (short)2);
        writeLEShort(raf, (short)16);
        raf.write(new byte[]{'d','a','t','a'});
        int dataSizePos = (int)raf.getFilePointer();
        writeLE(raf, 0);
        final int[] frames = new int[]{0};
        final int[] bytes = new int[]{0};
        encode(bmp, frame -> {
            try {
                for (int i = 0; i < frame.length; i++) {
                    int v = (int) Math.max(-32768, Math.min(32767, frame[i] * 32767.0));
                    raf.write(v & 0xff);
                    raf.write((v >> 8) & 0xff);
                    bytes[0] += 2;
                }
                frames[0]++;
                if (onFrame != null) onFrame.accept(frames[0]);
            } catch (java.io.IOException ignored) {}
        });
        int fileSize = (int)raf.length();
        raf.seek(4);
        writeLE(raf, fileSize - 8);
        raf.seek(dataSizePos);
        writeLE(raf, bytes[0]);
        raf.close();
    }


    private void writeLE(java.io.RandomAccessFile raf, int v) throws java.io.IOException { raf.write(v & 0xff); raf.write((v >> 8) & 0xff); raf.write((v >> 16) & 0xff); raf.write((v >> 24) & 0xff); }
    private void writeLE(java.io.RandomAccessFile raf, long v) throws java.io.IOException { raf.write((int)(v & 0xff)); raf.write((int)((v >> 8) & 0xff)); raf.write((int)((v >> 16) & 0xff)); raf.write((int)((v >> 24) & 0xff)); }
    private void writeLEShort(java.io.RandomAccessFile raf, short v) throws java.io.IOException { raf.write(v & 0xff); raf.write((v >> 8) & 0xff); }
}
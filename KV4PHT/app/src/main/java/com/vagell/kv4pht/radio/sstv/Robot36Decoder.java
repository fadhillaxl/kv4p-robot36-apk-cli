package com.vagell.kv4pht.radio.sstv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;

public class Robot36Decoder {
    public interface OnUpdate {
        void onBitmap(Bitmap bmp);
    }

    public interface OnComplete {
        void onBitmap(Bitmap bmp);
    }

    private final OnUpdate onUpdate;
    private final OnComplete onComplete;
    private final Bitmap preview = Bitmap.createBitmap(320, 256, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(preview);
    private final Paint paint = new Paint();

    private final List<Float> queue = new ArrayList<>();
    private int sampleRate = 48000;
    private int line = 0;
    private boolean started = false;

    private final int PIXELS = 320;
    private final int LINES = 240;

    private enum State {
        IDLE, CAL1, BREAK, CAL2, VIS_START, VIS_BITS, READY, LINES
    }

    private State state = State.IDLE;
    private int visCode = -1;

    public Robot36Decoder(OnUpdate onUpdate, OnComplete onComplete) {
        this.onUpdate = onUpdate;
        this.onComplete = onComplete;
        canvas.drawColor(Color.BLACK);
        paint.setStrokeWidth(1f);
        onUpdate.onBitmap(preview);
    }

    public void feed(float[] samples, int sr) {
        this.sampleRate = sr > 0 ? sr : this.sampleRate;
        for (float s : samples)
            queue.add(s);
        process();
    }

    private int msToSamples(int ms) {
        return (int) ((ms / 1000.0f) * sampleRate);
    }

    private float estimateFreq(List<Float> buf, int start, int len) {
        float e1500 = goertzel(buf, start, len, 1500f);
        float e2300 = goertzel(buf, start, len, 2300f);
        float frac = e2300 / (e1500 + e2300 + 1e-6f);
        return 1500f + frac * 800f;
    }

    private float goertzel(List<Float> x, int start, int len, float f) {
        float s = 0f, s1 = 0f, s2 = 0f;
        float w = (float) (2.0 * Math.PI * f / sampleRate);
        float c = (float) Math.cos(w);
        float k = 2f * c;
        int end = Math.min(start + len, x.size());
        for (int i = start; i < end; i++) {
            float v = x.get(i);
            s = k * s1 - s2 + v;
            s2 = s1;
            s1 = s;
        }
        float re = s1 - c * s2;
        float im = (float) Math.sin(w) * s2;
        float m = (float) Math.sqrt(re * re + im * im);
        return m;
    }

    private int mapToGray(float freq) {
        float v = (freq - 1500f) / 800f;
        int g = (int) (Math.max(0f, Math.min(1f, v)) * 255f);
        return Color.rgb(g, g, g);
    }

    private void process() {
        int sync = msToSamples(9);
        int porch = msToSamples(3);
        int yDur = msToSamples(88);
        int sep = msToSamples(4);
        int chroma = msToSamples(44);
        int perPixel = Math.max(1, yDur / PIXELS);
        if (state == State.IDLE) {
            int len = msToSamples(250);
            if (queue.size() >= len) {
                float e1900 = goertzel(queue, 0, len, 1900f);
                float e1200 = goertzel(queue, 0, len, 1200f);
                if (e1900 > e1200) {
                    state = State.CAL1;
                    remove(len);
                }
            }
            return;
        }
        if (state == State.CAL1) {
            int len = msToSamples(10);
            if (queue.size() >= len) {
                float e1200 = goertzel(queue, 0, len, 1200f);
                if (e1200 > 0.3f) {
                    state = State.BREAK;
                    remove(len);
                }
            }
            return;
        }
        if (state == State.BREAK) {
            int len = msToSamples(250);
            if (queue.size() >= len) {
                float e1900 = goertzel(queue, 0, len, 1900f);
                if (e1900 > 0.3f) {
                    state = State.CAL2;
                    remove(len);
                }
            }
            return;
        }
        if (state == State.CAL2) {
            int len = msToSamples(30);
            if (queue.size() >= len) {
                float e1200 = goertzel(queue, 0, len, 1200f);
                if (e1200 > 0.3f) {
                    state = State.VIS_BITS;
                    visCode = 0;
                    remove(len);
                }
            }
            return;
        }
        if (state == State.VIS_BITS) {
            int bitWin = msToSamples(30);
            int visBitIndex = 0;
            while (queue.size() >= bitWin && visBitIndex < 8) {
                float e1100 = goertzel(queue, 0, bitWin, 1100f);
                float e1300 = goertzel(queue, 0, bitWin, 1300f);
                int bit = e1100 > e1300 ? 1 : 0;
                visCode |= (bit << visBitIndex);
                visBitIndex++;
                remove(bitWin);
            }
            if (visBitIndex == 8) {
                int stopLen = msToSamples(30);
                if (queue.size() >= stopLen)
                    remove(stopLen);
                if (visCode == 0x28 || visCode == 0x08) {
                    state = State.READY;
                } else {
                    state = State.IDLE;
                    visCode = -1;
                }
            }
            return;
        }
        if (state == State.READY || state == State.LINES) {
            state = State.LINES;
            while (true) {
                if (line >= LINES) {
                    if (onComplete != null)
                        onComplete.onBitmap(preview.copy(Bitmap.Config.ARGB_8888, false));
                    state = State.IDLE;
                    break;
                }
                int needed = sync + porch + yDur + sep + porch + chroma;
                if (queue.size() < needed)
                    break;
                int idx = findSync(sync);
                if (idx < 0) {
                    remove(perPixel);
                    break;
                }
                int pos = idx + sync + porch;
                int[] yVals = new int[PIXELS];
                for (int x = 0; x < PIXELS; x++) {
                    int start = pos + x * perPixel;
                    int lenp = perPixel;
                    float f = estimateFreq(queue, start, lenp);
                    yVals[x] = clamp((int) ((f - 1500f) / 800f * 255f));
                }
                pos += yDur;
                pos += sep;
                pos += porch;
                int[] c160 = new int[160];
                int perC = Math.max(1, chroma / 160);
                for (int cx = 0; cx < 160; cx++) {
                    int start = pos + cx * perC;
                    float f = estimateFreq(queue, start, perC);
                    c160[cx] = clamp((int) ((f - 1500f) / 800f * 255f));
                }
                renderLineColor(line, yVals, c160);
                int toRemove = pos + chroma;
                remove(toRemove);
                line++;
                onUpdate.onBitmap(preview);
            }
        }
    }

    private int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private void remove(int n) {
        for (int i = 0; i < n && !queue.isEmpty(); i++)
            queue.remove(0);
    }

    private int findSync(int syncLen) {
        int win = syncLen;
        if (queue.size() < win)
            return -1;
        int step = Math.max(1, win / 3);
        for (int i = 0; i + win <= queue.size(); i += step) {
            float e1200 = goertzel(queue, i, win, 1200f);
            float e1500 = goertzel(queue, i, win, 1500f);
            if (e1200 > e1500)
                return i;
        }
        return -1;
    }

    private int[] lastCb = new int[160];
    private int[] lastCr = new int[160];

    private void renderLineColor(int ln, int[] yVals, int[] c160) {
        boolean even = (ln % 2 == 0);
        if (even)
            lastCr = c160.clone();
        else
            lastCb = c160.clone();
        for (int x = 0; x < PIXELS; x++) {
            int y = yVals[x];
            int cb = lastCb[Math.min(159, x / 2)];
            int cr = lastCr[Math.min(159, x / 2)];
            float fr = y + 1.403f * (cr - 128);
            float fb = y + 1.773f * (cb - 128);
            float fg = y - 0.714f * (cr - 128) - 0.344f * (cb - 128);
            int r = clamp((int) fr);
            int g = clamp((int) fg);
            int b = clamp((int) fb);
            paint.setColor(Color.rgb(r, g, b));
            canvas.drawLine(x, ln, x, ln, paint);
        }
    }
}
package com.vagell.kv4pht.sstv.robot36;

public class AudioResampler {
    private final int inRate;
    private final int outRate;
    private final double step;
    private double phase;
    private final float[] taps;
    private final int tapLen;
    private final float[] delay;
    private int dpos;
    private float dc;
    private final float dcAlpha = 0.001f;

    public AudioResampler(int inRate, int outRate) {
        this.inRate = inRate;
        this.outRate = outRate;
        this.step = inRate / (double) outRate;
        this.phase = 0.0;
        // FIR low-pass before downsample: cutoff ~ 5.5 kHz for Robot36
        double cutoff = 4500.0;
        int n = 129; // odd length, stronger LPF
        this.tapLen = n;
        this.taps = new float[n];
        Kaiser kaiser = new Kaiser();
        for (int i = 0; i < n; i++) {
            double w = kaiser.window(6.0, i, n);
            double h = Filter.lowPass(cutoff, inRate, i, n);
            taps[i] = (float) (w * h);
        }
        // normalize taps
        double sum = 0;
        for (int i = 0; i < n; i++)
            sum += taps[i];
        for (int i = 0; i < n; i++)
            taps[i] /= (float) sum;
        delay = new float[n];
        dpos = 0;
        dc = 0f;
    }

    public float[] process(float[] in) {
        if (in == null || in.length == 0)
            return new float[0];
        float[] filt = new float[in.length];
        float max = 0f;
        for (int i = 0; i < in.length; i++) {
            float x = in[i];
            dc += dcAlpha * (x - dc);
            x -= dc;
            delay[dpos] = x;
            dpos = (dpos + 1) % tapLen;
            double acc = 0;
            int idx = dpos - 1;
            for (int k = 0; k < tapLen; k++) {
                if (idx < 0)
                    idx += tapLen;
                acc += taps[k] * delay[idx];
                idx--;
            }
            float y = (float) acc;
            filt[i] = y;
            float a = Math.abs(y);
            if (a > max)
                max = a;
        }

        // Calculate exact number of output samples we can generate from this input
        // given the current phase.
        // We need filt[p] and filt[p+1]. So max index we can use is filt.length - 2.
        // If p == filt.length - 1, we need the *next* buffer's first sample, which we
        // don't have.
        // So we stop when idx >= filt.length - 1.

        int outCap = (int) Math.ceil(in.length / step) + 2;
        float[] tempOut = new float[outCap];
        int outCount = 0;

        float gain = max > 0 ? Math.min(2f, 0.6f / max) : 1f;

        while (phase < filt.length - 1) {
            int p = (int) phase;
            double frac = phase - p;
            float a = filt[p];
            float b = filt[p + 1];
            tempOut[outCount++] = gain * (float) (a + (b - a) * frac);
            phase += step;
        }

        // Adjust phase relative to the start of the *next* buffer
        phase -= in.length;

        // Copy to correctly sized array
        float[] out = new float[outCount];
        System.arraycopy(tempOut, 0, out, 0, outCount);
        return out;
    }
}
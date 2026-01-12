package com.vagell.kv4pht.sstv.robot36;

public class Hann {
    static double window(int n, int N) {
        return 0.5 * (1.0 - Math.cos((2.0 * Math.PI * n) / (N - 1)));
    }
}
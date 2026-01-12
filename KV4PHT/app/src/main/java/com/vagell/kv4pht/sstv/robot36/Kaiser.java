package com.vagell.kv4pht.sstv.robot36;

import java.util.Arrays;

public class Kaiser {
    double[] summands;

    Kaiser() {
        summands = new double[35];
    }

    private double square(double value) {
        return value * value;
    }

    private double i0(double x) {
        summands[0] = 1;
        double val = 1;
        for (int n = 1; n < summands.length; ++n)
            summands[n] = square(val *= x / (2 * n));
        Arrays.sort(summands);
        double sum = 0;
        for (int n = summands.length - 1; n >= 0; --n)
            sum += summands[n];
        return sum;
    }

    public double window(double a, int n, int N) {
        return i0(Math.PI * a * Math.sqrt(1 - square((2.0 * n) / (N - 1) - 1))) / i0(Math.PI * a);
    }
}
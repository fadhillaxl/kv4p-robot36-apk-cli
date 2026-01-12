package com.vagell.kv4pht.sstv.robot36;

public class SchmittTrigger {
    private final float low, high;
    private boolean previous;

    SchmittTrigger(float low, float high) {
        this.low = low;
        this.high = high;
    }

    boolean latch(float input) {
        if (previous) {
            if (input < low)
                previous = false;
        } else {
            if (input > high)
                previous = true;
        }
        return previous;
    }
}
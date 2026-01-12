package com.vagell.kv4pht.sstv.robot36;

public class SimpleMovingAverage extends SimpleMovingSum {
    public SimpleMovingAverage(int length) {
        super(length);
    }

    public float avg(float input) {
        return sum(input) / length;
    }
}
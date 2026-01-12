package com.vagell.kv4pht.sstv.robot36;

public class Complex {
    public float real, imag;

    public Complex() {
        real = 0;
        imag = 0;
    }

    public Complex(float real, float imag) {
        this.real = real;
        this.imag = imag;
    }

    public Complex set(Complex other) {
        real = other.real;
        imag = other.imag;
        return this;
    }

    public Complex set(float real, float imag) {
        this.real = real;
        this.imag = imag;
        return this;
    }

    public Complex set(float real) {
        return set(real, 0);
    }

    public float norm() {
        return real * real + imag * imag;
    }

    public float abs() {
        return (float) Math.sqrt(norm());
    }

    public float arg() {
        return (float) Math.atan2(imag, real);
    }

    public Complex polar(float a, float b) {
        real = a * (float) Math.cos(b);
        imag = a * (float) Math.sin(b);
        return this;
    }

    public Complex conj() {
        imag = -imag;
        return this;
    }

    public Complex add(Complex other) {
        real += other.real;
        imag += other.imag;
        return this;
    }

    public Complex sub(Complex other) {
        real -= other.real;
        imag -= other.imag;
        return this;
    }

    public Complex mul(float value) {
        real *= value;
        imag *= value;
        return this;
    }

    public Complex mul(Complex other) {
        float tmp = real * other.real - imag * other.imag;
        imag = real * other.imag + imag * other.real;
        real = tmp;
        return this;
    }

    public Complex div(float value) {
        real /= value;
        imag /= value;
        return this;
    }

    public Complex div(Complex other) {
        float den = other.norm();
        float tmp = (real * other.real + imag * other.imag) / den;
        imag = (imag * other.real - real * other.imag) / den;
        real = tmp;
        return this;
    }
}
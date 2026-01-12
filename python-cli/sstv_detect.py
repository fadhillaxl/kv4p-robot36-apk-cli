import numpy as np
from collections import deque

def goertzel_power(x: np.ndarray, f: float, fs: int) -> float:
    N = len(x)
    if N == 0:
        return 0.0
    k = int(0.5 + (N * f / fs))
    omega = (2.0 * np.pi * k) / N
    coeff = 2.0 * np.cos(omega)
    s_prev = 0.0
    s_prev2 = 0.0
    for sample in x:
        s = sample + coeff * s_prev - s_prev2
        s_prev2 = s_prev
        s_prev = s
    return max(0.0, s_prev2 * s_prev2 + s_prev * s_prev - coeff * s_prev * s_prev2) / N

class SSTVDetector:
    def __init__(self, recorder_provider, sample_rate=48000, finalize_cb=None):
        self.recorder_provider = recorder_provider
        self.fs = sample_rate
        self.state = 'idle'
        self.leader_ms = 300
        self.leader_freq = 1900.0
        self.band_freqs = (1200.0, 1500.0, 1900.0, 2300.0)
        self.leader_accum = 0.0
        self.silence_ms = 0.0
        self.max_ms = 60000.0
        self.record_ms = 0.0
        self.finalize_cb = finalize_cb
        self.pre_seconds = 3.0
        self.prebuf = deque()
        self.pre_samples = 0
        self.noise_floor = 1e-6
        self.noise_alpha = 0.995
        self.snr_threshold_start = 6.0
        self.snr_threshold_hold = 3.0
        self.start_frames_required = int(0.5 * self.fs / self._frame_len_samples())  # ~500ms
        self.stop_frames_required = int(1.5 * self.fs / self._frame_len_samples())   # ~1500ms
        self.frames_above = 0
        self.frames_below = 0

    def _frame_len_samples(self):
        return max(256, int(self.fs * 0.04))  # ~40ms frames for stability

    def process(self, samples: np.ndarray):
        frame_ms = (len(samples) / self.fs) * 1000.0
        # Down-sample to fixed frame length for robust power
        Nf = self._frame_len_samples()
        if len(samples) > Nf:
            x = samples[:Nf]
        else:
            pad = np.zeros(Nf - len(samples), dtype=np.float32)
            x = np.concatenate([samples, pad])
        p_leader = goertzel_power(x, self.leader_freq, self.fs)
        p_band = sum(goertzel_power(x, f, self.fs) for f in self.band_freqs)
        amp = float(np.mean(np.abs(x)))
        # Update noise floor when idle
        if self.state == 'idle':
            self.noise_floor = self.noise_alpha * self.noise_floor + (1.0 - self.noise_alpha) * max(p_band, amp * amp)
        noise = max(self.noise_floor, 1e-6)
        snr = p_band / noise
        leader_hit = p_leader > (5.0 * noise)
        band_hit = snr > self.snr_threshold_start or (amp > 0.02)

        self.prebuf.append(samples.copy())
        self.pre_samples += len(samples)
        max_pre = int(self.fs * self.pre_seconds)
        while self.pre_samples > max_pre and len(self.prebuf) > 0:
            a = self.prebuf.popleft()
            self.pre_samples -= len(a)

        if self.state == 'idle':
            if leader_hit or band_hit:
                self.leader_accum += frame_ms
                if self.leader_accum >= self.leader_ms:
                    rec = self.recorder_provider()
                    if rec is not None:
                        rec.start()
                        if self.pre_samples > 0:
                            rec.add_samples(np.concatenate(list(self.prebuf)))
                        self.state = 'recording'
                        self.record_ms = 0.0
                        self.silence_ms = 0.0
                        self.frames_above = 0
                        self.frames_below = 0
            else:
                self.leader_accum = 0.0
        elif self.state == 'recording':
            self.record_ms += frame_ms
            # Hysteresis based on SNR
            if snr > self.snr_threshold_hold:
                self.frames_above += 1
                self.frames_below = 0
            else:
                self.frames_below += 1
                self.frames_above = 0
            if self.frames_below * (1000.0 * Nf / self.fs / 1000.0) > 1500.0 or self.record_ms > self.max_ms:
                rec = self.recorder_provider()
                if rec is not None:
                    out_path = rec.stop()
                    if self.finalize_cb:
                        self.finalize_cb(out_path)
                self.state = 'idle'
                self.leader_accum = 0.0
                self.record_ms = 0.0
                self.silence_ms = 0.0
                self.frames_above = 0
                self.frames_below = 0
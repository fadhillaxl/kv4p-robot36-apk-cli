#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "robot36_decode.h"

struct r36dec_ctx {
    int in_sr;
    int target_sr;
    double phase;
    float* buf;
    int buf_len;
    int buf_cap;
    uint32_t* argb;
    int line;
    int state;
    int completed;
    int lastCb[160];
    int lastCr[160];
    int visBits;
    int visIdx;
};

static int ms_to_samples(int sr, int ms) { return (sr * ms) / 1000; }
static int clampi(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

static void push(r36dec_ctx* c, float s) {
    if (c->buf_len >= c->buf_cap) {
        c->buf_cap = c->buf_cap ? c->buf_cap * 2 : 65536;
        c->buf = (float*)realloc(c->buf, c->buf_cap * sizeof(float));
    }
    c->buf[c->buf_len++] = s;
}

static float goertzel(const float* x, int start, int len, float f, int sr) {
    float s=0.f,s1=0.f,s2=0.f;
    float w = (float)(2.0 * M_PI * f / sr);
    float c = (float)cos(w);
    float k = 2.f * c;
    int end = start + len;
    for (int i = start; i < end; i++) {
        float v = x[i];
        s = k * s1 - s2 + v;
        s2 = s1;
        s1 = s;
    }
    float re = s1 - c * s2;
    float im = (float)sin(w) * s2;
    float m = (float)sqrt(re * re + im * im);
    return m;
}

static float estimate_freq(const float* buf, int start, int len, int sr) {
    // Enhanced frequency estimation with smoothing
    
    if (len < 4) {
        // Simple zero crossing for very short windows
        int crossings = 0;
        for (int i = start + 1; i < start + len; i++) {
            if ((buf[i-1] < 0 && buf[i] >= 0) || (buf[i-1] >= 0 && buf[i] < 0)) {
                crossings++;
            }
        }
        float duration = (float)len / sr;
        float freq = crossings / (2.0f * duration);
        return (freq < 1200.f) ? 1200.f : ((freq > 2500.f) ? 2500.f : freq);
    }
    
    // Method 1: Zero Crossing with Linear Interpolation (Primary)
    double total_freq = 0;
    int count = 0;
    
    for (int i = start + 1; i < start + len; i++) {
        float s0 = buf[i-1];
        float s1 = buf[i];
        
        if (s0 < 0 && s1 >= 0) {
            float frac = -s0 / (s1 - s0 + 1e-10f);
            
            for (int j = i + 1; j < start + len; j++) {
                float s2 = buf[j-1];
                float s3 = buf[j];
                if (s2 < 0 && s3 >= 0) {
                    float frac2 = -s2 / (s3 - s2 + 1e-10f);
                    float period_samples = (j - i) + (frac2 - frac);
                    if (period_samples > 0) {
                        float freq = sr / period_samples;
                        if (freq >= 1100.f && freq <= 2800.f) {
                            total_freq += freq;
                            count++;
                        }
                    }
                    break;
                }
            }
        }
    }
    
    float zc_freq = 0;
    if (count > 0) {
        zc_freq = total_freq / count;
    }
    
    // Method 2: Goertzel-based Center of Gravity (Secondary/Fallback)
    // Helps when zero-crossing is noisy or ambiguous
    float e1200 = goertzel(buf, start, len, 1200.f, sr);
    float e1500 = goertzel(buf, start, len, 1500.f, sr);
    float e1900 = goertzel(buf, start, len, 1900.f, sr);
    float e2300 = goertzel(buf, start, len, 2300.f, sr);
    
    float sum_energy = e1200 + e1500 + e1900 + e2300 + 1e-6f;
    float weighted_freq = (e1200*1200 + e1500*1500 + e1900*1900 + e2300*2300) / sum_energy;
    
    // Combine results
    // If zero-crossing is stable, use it. If not, blend with weighted spectral energy.
    float final_freq;
    if (count > 0 && zc_freq > 1100.f && zc_freq < 2600.f) {
        final_freq = zc_freq * 0.7f + weighted_freq * 0.3f;
    } else {
        final_freq = weighted_freq;
    }

    // Clamp result to valid SSTV range
    if (final_freq < 1500.f) final_freq = 1500.f;
    if (final_freq > 2300.f) final_freq = 2300.f;
    
    return final_freq;
}

static int find_sync(const float* buf, int buf_len, int win, int sr) {
    int step = win > 3 ? win / 3 : 1;
    for (int i = 0; i + win <= buf_len; i += step) {
        float e1200 = goertzel(buf, i, win, 1200.f, sr);
        float e1500 = goertzel(buf, i, win, 1500.f, sr);
        if (e1200 > e1500) return i;
    }
    return -1;
}

static void render_line_color(r36dec_ctx* c, int ln, const int* yVals, const int* c160, int is_even) {
    // Robot36 encoder sends: even lines = Cr (channel 2), odd lines = Cb (channel 1)
    // We store the chroma for the current line type
    if (is_even) { 
        for (int i=0;i<160;i++) c->lastCr[i] = c160[i]; 
    } else { 
        for (int i=0;i<160;i++) c->lastCb[i] = c160[i]; 
    }
    
    // Render using Y and both chroma channels
    // Note: On first even line, lastCb will be uninitialized (128 = neutral)
    // On first odd line, lastCr will be from previous even line
    for (int x=0; x<320; x++) {
        int y = yVals[x];
        int cb = c->lastCb[x/2 > 159 ? 159 : x/2];
        int cr = c->lastCr[x/2 > 159 ? 159 : x/2];
        
        // YCbCr to RGB conversion (ITU-R BT.601)
        float fr = y + 1.403f * (cr - 128);
        float fb = y + 1.773f * (cb - 128);
        float fg = y - 0.714f * (cr - 128) - 0.344f * (cb - 128);
        int r = clampi((int)fr);
        int g = clampi((int)fg);
        int b = clampi((int)fb);
        c->argb[ln * 320 + x] = (0xFF<<24) | (r<<16) | (g<<8) | b;
    }
}

enum { ST_IDLE=0, ST_CAL1, ST_BREAK, ST_CAL2, ST_VIS, ST_READY, ST_LINES };

r36dec_ctx* r36dec_create(int input_sr) {
    r36dec_ctx* c = (r36dec_ctx*)calloc(1, sizeof(r36dec_ctx));
    c->in_sr = input_sr > 0 ? input_sr : 48000;
    c->target_sr = 44100;  // Use higher sample rate for better frequency resolution
    c->buf = NULL;
    c->buf_len = 0;
    c->buf_cap = 0;
    c->argb = (uint32_t*)malloc(320 * 256 * sizeof(uint32_t));
    for (int i=0;i<320*256;i++) c->argb[i] = 0xFF000000;
    c->line = 0;
    c->state = ST_IDLE;
    c->completed = 0;
    c->visBits = 0;
    c->visIdx = 0;
    // Initialize chroma to neutral (128 = no color shift)
    for (int i=0;i<160;i++) { c->lastCb[i] = 128; c->lastCr[i] = 128; }
    return c;
}

void r36dec_destroy(r36dec_ctx* c) {
    if (!c) return;
    if (c->buf) free(c->buf);
    if (c->argb) free(c->argb);
    free(c);
}

static void resample_and_push(r36dec_ctx* c, const float* in, int n, int input_sr) {
    // If input rate matches target, just copy
    int actual_sr = input_sr > 0 ? input_sr : c->in_sr;
    if (actual_sr == c->target_sr) {
        for (int i = 0; i < n; i++) {
            push(c, in[i]);
        }
        return;
    }
    
    // Simple linear interpolation resampling
    double ratio = (double)actual_sr / (double)c->target_sr;
    int out_samples = (int)(n / ratio);
    
    for (int i = 0; i < out_samples; i++) {
        double src_pos = i * ratio;
        int src_idx = (int)src_pos;
        double frac = src_pos - src_idx;
        
        if (src_idx >= n - 1) {
            push(c, in[n - 1]);
        } else {
            float s = (float)((1.0 - frac) * in[src_idx] + frac * in[src_idx + 1]);
            push(c, s);
        }
    }
}

void r36dec_feed(r36dec_ctx* c, const float* samples, int n, int input_sr) {
    if (!c || !samples || n <= 0) return;
    resample_and_push(c, samples, n, input_sr);
    int sr = c->target_sr;
    int sync = ms_to_samples(sr, 9);
    int porch = ms_to_samples(sr, 3);
    int yDur = ms_to_samples(sr, 88);
    // Corrected timings to match Robot36 spec and encoder
    int sep = (sr * 45) / 10000; // 4.5ms
    int porchBack = (sr * 15) / 10000; // 1.5ms
    int chroma = ms_to_samples(sr, 44);
    int perPixel = yDur / 320 > 0 ? yDur / 320 : 1;
    while (!c->completed) {
        if (c->state == ST_IDLE) {
            int len = ms_to_samples(sr, 250);
            if (c->buf_len >= len) {
                float e1900 = goertzel(c->buf, 0, len, 1900.f, sr);
                float e1200 = goertzel(c->buf, 0, len, 1200.f, sr);
                if (e1900 > e1200) { c->state = ST_CAL1; c->buf_len = c->buf_len - len; memmove(c->buf, c->buf+len, c->buf_len*sizeof(float)); }
                else {
                    // Slide window: consume small amount
                    int step = ms_to_samples(sr, 10);
                    c->buf_len = c->buf_len - step;
                    memmove(c->buf, c->buf+step, c->buf_len*sizeof(float));
                }
            } else break;
        } else if (c->state == ST_CAL1) {
            int len = ms_to_samples(sr, 10);
            if (c->buf_len >= len) {
                float e1200 = goertzel(c->buf, 0, len, 1200.f, sr);
                if (e1200 > 0.3f) { c->state = ST_BREAK; c->buf_len = c->buf_len - len; memmove(c->buf, c->buf+len, c->buf_len*sizeof(float)); }
                else {
                     // Check if still 1900Hz (Leader extension)
                     float e1900 = goertzel(c->buf, 0, len, 1900.f, sr);
                     if (e1900 > 0.3f) {
                         // Still leader, consume it
                         c->buf_len = c->buf_len - len; memmove(c->buf, c->buf+len, c->buf_len*sizeof(float));
                     } else {
                         // Lost sync? Or just noise? Go back to IDLE?
                         // For now, let's just break/wait or consume?
                         // If we consume, we might miss the 1200Hz if it's coming.
                         // But if we don't consume, we get stuck.
                         // Let's assume if it's not 1200 and not 1900, it's garbage/transition.
                         // Consume it.
                         c->buf_len = c->buf_len - len; memmove(c->buf, c->buf+len, c->buf_len*sizeof(float));
                     }
                }
            } else break;
        } else if (c->state == ST_BREAK) {
            int len = ms_to_samples(sr, 250);
            if (c->buf_len >= len) {
                float e1900 = goertzel(c->buf, 0, len, 1900.f, sr);
                if (e1900 > 0.3f) { c->state = ST_CAL2; c->buf_len = c->buf_len - len; memmove(c->buf, c->buf+len, c->buf_len*sizeof(float)); }
                else {
                    // Slide window
                    int step = ms_to_samples(sr, 10);
                    c->buf_len = c->buf_len - step;
                    memmove(c->buf, c->buf+step, c->buf_len*sizeof(float));
                }
            } else break;
        } else if (c->state == ST_CAL2) {
            int len = ms_to_samples(sr, 30);
            if (c->buf_len >= len) {
                float e1200 = goertzel(c->buf, 0, len, 1200.f, sr);
                if (e1200 > 0.3f) { c->state = ST_VIS; c->visBits = 0; c->visIdx = 0; c->buf_len = c->buf_len - len; memmove(c->buf, c->buf+len, c->buf_len*sizeof(float)); }
                else {
                    // Handle leader extension (1900Hz)
                    float e1900 = goertzel(c->buf, 0, len, 1900.f, sr);
                    if (e1900 > 0.3f) {
                        c->buf_len = c->buf_len - len; memmove(c->buf, c->buf+len, c->buf_len*sizeof(float));
                    } else {
                        // Consume garbage
                        c->buf_len = c->buf_len - len; memmove(c->buf, c->buf+len, c->buf_len*sizeof(float));
                    }
                }
            } else break;
        } else if (c->state == ST_VIS) {
            int bitWin = ms_to_samples(sr, 30);
            while (c->buf_len >= bitWin && c->visIdx < 8) {
                float e1100 = goertzel(c->buf, 0, bitWin, 1100.f, sr);
                float e1300 = goertzel(c->buf, 0, bitWin, 1300.f, sr);
                int bit = e1100 > e1300 ? 1 : 0;
                c->visBits |= (bit << c->visIdx);
                c->visIdx++;
                c->buf_len = c->buf_len - bitWin; memmove(c->buf, c->buf+bitWin, c->buf_len*sizeof(float));
            }
            if (c->visIdx == 8) {
                int stopLen = ms_to_samples(sr, 30);
                if (c->buf_len >= stopLen) { 
                    c->buf_len = c->buf_len - stopLen; memmove(c->buf, c->buf+stopLen, c->buf_len*sizeof(float)); 
                    if ((c->visBits & 0x7F) == 0x28 || (c->visBits & 0x7F) == 0x08) c->state = ST_READY; 
                    else { c->state = ST_IDLE; c->line = 0; }
                } else break; // Wait for stop bit
            } else break;
        } else if (c->state == ST_READY || c->state == ST_LINES) {
            c->state = ST_LINES;
            int needed = sync + porch + yDur + sep + porch + chroma;
            if (c->buf_len < needed) break;
            int idx = find_sync(c->buf, c->buf_len, sync, sr);
            if (idx < 0) { int r = perPixel; if (c->buf_len >= r) { c->buf_len = c->buf_len - r; memmove(c->buf, c->buf+r, c->buf_len*sizeof(float)); } break; }
            int pos = idx + sync + porch;
            int yVals[320];
            
            // Use longer window (at least 30 samples) for better frequency estimation
            // with overlapping/interpolation for per-pixel values
            int minWindow = 30;  // ~0.7ms at 44100Hz - about half a period at 1500Hz
            
            if (perPixel >= minWindow) {
                // Enough samples per pixel - use direct measurement
                for (int x=0;x<320;x++) {
                    int start = pos + x * perPixel;
                    float f = estimate_freq(c->buf, start, perPixel, sr);
                    int y = clampi((int)(((f - 1500.f) / 800.f) * 255.f));
                    yVals[x] = y;
                }
            } else {
                // Not enough samples per pixel - use longer window and interpolate
                int stepPixels = (minWindow + perPixel - 1) / perPixel;  // How many pixels fit in minWindow
                if (stepPixels < 1) stepPixels = 1;
                int windowSamples = stepPixels * perPixel;
                
                // Sample at regular intervals
                float freqs[321];  // One extra for interpolation
                int numSamples = (320 + stepPixels - 1) / stepPixels + 1;
                for (int i = 0; i < numSamples && i * stepPixels <= 320; i++) {
                    int x = i * stepPixels;
                    if (x > 320) x = 320;
                    int start = pos + x * perPixel;
                    int actualWindow = windowSamples;
                    if (start + actualWindow > pos + yDur) actualWindow = pos + yDur - start;
                    if (actualWindow < minWindow) actualWindow = minWindow;
                    freqs[i] = estimate_freq(c->buf, start, actualWindow, sr);
                }
                
                // Linear interpolation for each pixel
                for (int x = 0; x < 320; x++) {
                    int idx1 = x / stepPixels;
                    int idx2 = idx1 + 1;
                    if (idx2 >= numSamples) idx2 = numSamples - 1;
                    float frac = (float)(x % stepPixels) / stepPixels;
                    float f = freqs[idx1] * (1.0f - frac) + freqs[idx2] * frac;
                    int y = clampi((int)(((f - 1500.f) / 800.f) * 255.f));
                    yVals[x] = y;
                }
            }
            pos += yDur;
            
            // Detect even/odd from separator frequency
            // Even lines have separator at 1500Hz (black), odd lines at 2300Hz (white)
            int sep_start = pos;
            float sep_freq = estimate_freq(c->buf, sep_start, sep, sr);
            int is_even = (sep_freq < 1900.f);  // Below center frequency = even (Cr)
            
            pos += sep;
            pos += porchBack; // Use correct back porch duration (1.5ms)
            int c160[160];
            int perC = chroma / 160 > 0 ? chroma / 160 : 1;
            
            // Same approach for chroma
            if (perC >= minWindow) {
                for (int cx=0; cx<160; cx++) {
                    int start = pos + cx * perC;
                    float f = estimate_freq(c->buf, start, perC, sr);
                    c160[cx] = clampi((int)(((f - 1500.f) / 800.f) * 255.f));
                }
            } else {
                int stepPixelsC = (minWindow + perC - 1) / perC;
                if (stepPixelsC < 1) stepPixelsC = 1;
                int windowSamplesC = stepPixelsC * perC;
                
                float cfreqs[161];
                int numSamplesC = (160 + stepPixelsC - 1) / stepPixelsC + 1;
                for (int i = 0; i < numSamplesC && i * stepPixelsC <= 160; i++) {
                    int cx = i * stepPixelsC;
                    if (cx > 160) cx = 160;
                    int start = pos + cx * perC;
                    int actualWindow = windowSamplesC;
                    if (start + actualWindow > pos + chroma) actualWindow = pos + chroma - start;
                    if (actualWindow < minWindow) actualWindow = minWindow;
                    cfreqs[i] = estimate_freq(c->buf, start, actualWindow, sr);
                }
                
                for (int cx = 0; cx < 160; cx++) {
                    int idx1 = cx / stepPixelsC;
                    int idx2 = idx1 + 1;
                    if (idx2 >= numSamplesC) idx2 = numSamplesC - 1;
                    float frac = (float)(cx % stepPixelsC) / stepPixelsC;
                    float f = cfreqs[idx1] * (1.0f - frac) + cfreqs[idx2] * frac;
                    c160[cx] = clampi((int)(((f - 1500.f) / 800.f) * 255.f));
                }
            }
            render_line_color(c, c->line, yVals, c160, is_even);
            int toRemove = pos + chroma;
            if (c->buf_len >= toRemove) { c->buf_len = c->buf_len - toRemove; memmove(c->buf, c->buf+toRemove, c->buf_len*sizeof(float)); }
            c->line++;
            if (c->line >= 240) { c->completed = 1; break; }
        } else break;
    }
}

int r36dec_get_line_index(r36dec_ctx* c) { return c ? c->line : 0; }

const char* r36dec_get_state(r36dec_ctx* c) {
    if (!c) return "";
    switch (c->state) {
        case ST_IDLE: return "Idle";
        case ST_CAL1: return "Leader";
        case ST_BREAK: return "Break";
        case ST_CAL2: return "Leader";
        case ST_VIS: return "Vis";
        case ST_READY: return "Ready";
        case ST_LINES: return "Image";
        default: return "";
    }
}

int r36dec_is_completed(r36dec_ctx* c) { return c ? c->completed : 0; }

uint32_t* r36dec_get_argb(r36dec_ctx* c) { return c ? c->argb : NULL; }
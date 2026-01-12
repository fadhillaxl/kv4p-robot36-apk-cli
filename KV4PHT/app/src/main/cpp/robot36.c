#include <stdint.h>
#include <stdlib.h>
#include <math.h>

static void write_le32(uint8_t *p, uint32_t v) { p[0]=v&0xff; p[1]=(v>>8)&0xff; p[2]=(v>>16)&0xff; p[3]=(v>>24)&0xff; }
static void write_le16(uint8_t *p, uint16_t v) { p[0]=v&0xff; p[1]=(v>>8)&0xff; }

struct r36_ctx {
    int sr;
    float phase;
    int16_t *pcm;
    int pcm_len;
    int pcm_cap;
};

static void r36_push(struct r36_ctx *c, int16_t s) {
    if (c->pcm_len >= c->pcm_cap) {
        c->pcm_cap = c->pcm_cap ? c->pcm_cap * 2 : 65536;
        c->pcm = (int16_t*)realloc(c->pcm, c->pcm_cap * sizeof(int16_t));
    }
    c->pcm[c->pcm_len++] = s;
}

static void r36_tone(struct r36_ctx *c, float f, int samples) {
    for (int i=0;i<samples;i++) {
        c->phase += (float)(2.0 * 3.14159265358979323846 * f / c->sr);
        if (c->phase > (float)(2.0 * 3.14159265358979323846)) c->phase -= (float)(2.0 * 3.14159265358979323846);
        float s = (float)sin(c->phase);
        int v = (int)(s * 32767.0f);
        if (v < -32768) v = -32768; if (v > 32767) v = 32767;
        r36_push(c, (int16_t)v);
    }
}

static int ms_to_samples(struct r36_ctx *c, int ms) { return (c->sr * ms) / 1000; }

static int clamp(int v) { return v<0?0:(v>255?255:v); }

static void vis(struct r36_ctx *c) {
    r36_tone(c, 1900.0f, ms_to_samples(c,300));
    r36_tone(c, 1200.0f, ms_to_samples(c,10));
    r36_tone(c, 1900.0f, ms_to_samples(c,300));
    r36_tone(c, 1200.0f, ms_to_samples(c,30));
    int code7 = 0x28 & 0x7F; int ones=0;
    for (int i=0;i<7;i++) { int bit=(code7>>i)&1; if(bit) ones++; r36_tone(c, bit?1100.0f:1300.0f, ms_to_samples(c,30)); }
    int parity_even = (ones%2)==0; r36_tone(c, parity_even?1300.0f:1100.0f, ms_to_samples(c,30));
    r36_tone(c, 1200.0f, ms_to_samples(c,30));
}

static float map_freq(int v) { return 1500.0f + (v / 255.0f) * 800.0f; }

static int y_from_rgb(int r,int g,int b){ return clamp((int)(0.299f*r+0.587f*g+0.114f*b)); }
static int cb_from_rgb(int r,int g,int b,int y){ return clamp((int)(128 + 0.564f * (b - y))); }
static int cr_from_rgb(int r,int g,int b,int y){ return clamp((int)(128 + 0.713f * (r - y))); }

static void line(struct r36_ctx *c, const uint32_t *row, int w, int ln) {
    r36_tone(c, 1200.0f, ms_to_samples(c,9));
    r36_tone(c, 1500.0f, ms_to_samples(c,3));
    int yTot = ms_to_samples(c,88);
    int yPer = yTot / 320; int yRem = yTot - yPer*320;
    for (int x=0;x<320;x++) {
        uint32_t p = row[x]; int r=(p>>16)&0xff; int g=(p>>8)&0xff; int b=p&0xff;
        int y = y_from_rgb(r,g,b);
        float f = map_freq(y);
        int ns = yPer + (x < yRem ? 1 : 0);
        r36_tone(c, f, ns);
    }
    r36_tone(c, 1900.0f, ms_to_samples(c,4));
    r36_tone(c, 1500.0f, ms_to_samples(c,3));
    int cTot = ms_to_samples(c,44);
    int cPer = cTot / 160; int cRem = cTot - cPer*160;
    for (int x=0;x<160;x++) {
        int x0=x*2; uint32_t p0=row[x0]; uint32_t p1=row[x0+1<w?x0+1:w-1];
        int r0=(p0>>16)&0xff,g0=(p0>>8)&0xff,b0=p0&0xff; int r1=(p1>>16)&0xff,g1=(p1>>8)&0xff,b1=p1&0xff;
        int y0=y_from_rgb(r0,g0,b0); int y1=y_from_rgb(r1,g1,b1);
        int cb=(cb_from_rgb(r0,g0,b0,y0)+cb_from_rgb(r1,g1,b1,y1))/2; int cr=(cr_from_rgb(r0,g0,b0,y0)+cr_from_rgb(r1,g1,b1,y1))/2;
        int chroma = (ln%2==0)?cr:cb;
        float f = map_freq(chroma);
        int ns = cPer + (x < cRem ? 1 : 0);
        r36_tone(c, f, ns);
    }
}

int robot36_encode_wav_11025(const uint32_t *pixels, int w, int h, uint8_t **wav_out, int *wav_len) {
    struct r36_ctx ctx; ctx.sr=11025; ctx.phase=0.0f; ctx.pcm=NULL; ctx.pcm_len=0; ctx.pcm_cap=0;
    vis(&ctx);
    int lines = h<240?h:240;
    for (int ln=0; ln<lines; ln++) line(&ctx, pixels + ln * w, w, ln);
    int tail = ms_to_samples(&ctx,700);
    for (int i=0;i<tail;i++) r36_push(&ctx, 0);
    int data_bytes = ctx.pcm_len * 2;
    int riff_size = 36 + data_bytes;
    int wav_size = 44 + data_bytes;
    uint8_t *buf = (uint8_t*)malloc(wav_size);
    buf[0]='R';buf[1]='I';buf[2]='F';buf[3]='F'; write_le32(buf+4, riff_size); buf[8]='W';buf[9]='A';buf[10]='V';buf[11]='E';
    buf[12]='f';buf[13]='m';buf[14]='t';buf[15]=' '; write_le32(buf+16,16); write_le16(buf+20,1); write_le16(buf+22,1);
    write_le32(buf+24,11025); write_le32(buf+28,11025*2); write_le16(buf+32,2); write_le16(buf+34,16);
    buf[36]='d';buf[37]='a';buf[38]='t';buf[39]='a'; write_le32(buf+40, data_bytes);
    for (int i=0;i<ctx.pcm_len;i++) { int16_t s=ctx.pcm[i]; buf[44+i*2]=s&0xff; buf[45+i*2]=(s>>8)&0xff; }
    free(ctx.pcm);
    *wav_out = buf; *wav_len = wav_size; return 0;
}
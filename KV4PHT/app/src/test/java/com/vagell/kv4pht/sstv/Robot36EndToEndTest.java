package com.vagell.kv4pht.sstv;

import com.vagell.kv4pht.sstv.robot36.Decoder;
import com.vagell.kv4pht.sstv.robot36.PixelBuffer;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertTrue;

public class Robot36EndToEndTest {

    private static class WavInfo {
        int sampleRate;
        int bitsPerSample;
        int channels;
        long dataOffset;
        long dataSize;
    }

    private WavInfo parseWavHeader(FileInputStream fis) throws IOException {
        byte[] hdr = new byte[44];
        if (fis.read(hdr) < 44) throw new IOException("Invalid WAV header");
        ByteBuffer b = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x46464952) throw new IOException("Not RIFF");
        if (b.getInt(8) != 0x45564157) throw new IOException("Not WAVE");
        int fmtIdx = 12;
        while (fmtIdx + 8 <= hdr.length) {
            int id = b.getInt(fmtIdx);
            int len = b.getInt(fmtIdx + 4);
            if (id == 0x20746D66) break;
            fmtIdx += 8 + len;
        }
        int audioFormat = b.getShort(fmtIdx + 8) & 0xffff;
        int channels = b.getShort(fmtIdx + 10) & 0xffff;
        int sampleRate = b.getInt(fmtIdx + 12);
        int bitsPerSample = b.getShort(fmtIdx + 22) & 0xffff;
        long pos = 12;
        while (true) {
            fis.getChannel().position(pos);
            byte[] chunkHdr = new byte[8];
            if (fis.read(chunkHdr) < 8) throw new IOException("No data chunk");
            ByteBuffer ch = ByteBuffer.wrap(chunkHdr).order(ByteOrder.LITTLE_ENDIAN);
            int id = ch.getInt(0);
            int len = ch.getInt(4);
            if (id == 0x61746164) {
                WavInfo info = new WavInfo();
                info.sampleRate = sampleRate;
                info.bitsPerSample = bitsPerSample;
                info.channels = channels;
                info.dataOffset = pos + 8;
                info.dataSize = len;
                return info;
            }
            pos += 8L + len;
        }
    }

    private float[] readFrame(FileInputStream fis, WavInfo info, int frames) throws IOException {
        int stride = info.bitsPerSample / 8;
        byte[] buf = new byte[stride * frames];
        int n = fis.read(buf);
        if (n <= 0) return null;
        int availFrames = n / stride;
        float[] out = new float[availFrames];
        ByteBuffer bb = ByteBuffer.wrap(buf, 0, availFrames * stride).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < availFrames; i++) {
            int p = i * stride;
            float sample;
            if (stride == 2) sample = bb.getShort(p) / 32768f;
            else if (stride == 1) sample = ((buf[p] & 0xff) - 128) / 128f;
            else if (stride == 4) sample = Math.max(-1f, Math.min(1f, bb.getInt(p) / (float)Integer.MAX_VALUE));
            else sample = 0;
            out[i] = sample;
        }
        return out;
    }

    private static class AvgRGB {
        float r, g, b;
    }

    private AvgRGB[] averageBars(int[] pixels, int width, int height, int bars) {
        AvgRGB[] out = new AvgRGB[bars];
        for (int i = 0; i < bars; i++) out[i] = new AvgRGB();
        int y0 = height / 3;
        int y1 = height * 2 / 3;
        for (int y = y0; y < y1; y++) {
            for (int b = 0; b < bars; b++) {
                int x0 = b * width / bars;
                int x1 = (b + 1) * width / bars;
                for (int x = x0; x < x1; x++) {
                    int argb = pixels[y * width + x];
                    int r = (argb >> 16) & 0xff;
                    int g = (argb >> 8) & 0xff;
                    int bch = (argb) & 0xff;
                    out[b].r += r;
                    out[b].g += g;
                    out[b].b += bch;
                }
            }
        }
        int denom = (y1 - y0) * (width / bars);
        for (int i = 0; i < bars; i++) {
            out[i].r /= denom;
            out[i].g /= denom;
            out[i].b /= denom;
        }
        return out;
    }

    @Test
    public void decodePythonCliRobot36Wav() throws Exception {
        File wav = new File("/Users/mm/GitHub/kv4p-robot36-apk/KV4PHT/app/build/robot36_python_cli.wav");
        assertTrue("WAV tidak ditemukan: " + wav, wav.exists());
        try (FileInputStream fis = new FileInputStream(wav)) {
            WavInfo info = parseWavHeader(fis);
            fis.getChannel().position(info.dataOffset);
            PixelBuffer scope = new PixelBuffer(640, 2 * 1280);
            PixelBuffer image = new PixelBuffer(800, 616);
            Decoder decoder = new Decoder(scope, image, "Raw", info.sampleRate);
            int chunk = Math.max(512, info.sampleRate / 100);
            while (true) {
                float[] frame = readFrame(fis, info, chunk);
                if (frame == null) break;
                decoder.process(frame, 0);
            }
            assertTrue("Gambar belum ter-decode", image.line > 0);

            int h = Math.min(image.line, image.height);
            AvgRGB[] got = averageBars(image.pixels, image.width, h, 6);

            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new File("/Users/mm/GitHub/kv4p-robot36-apk/python-cli/out/testimage/simple_colorbars.png"));
            java.awt.image.BufferedImage srcScaled = new java.awt.image.BufferedImage(320, 240, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g2 = srcScaled.createGraphics();
            g2.drawImage(src.getScaledInstance(320, 240, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
            g2.dispose();
            int[] srcPixels = new int[320 * 240];
            srcScaled.getRGB(0, 0, 320, 240, srcPixels, 0, 320);
            AvgRGB[] exp = averageBars(srcPixels, 320, 240, 6);

            for (int i = 0; i < 6; i++) {
                float dr = Math.abs(got[i].r - exp[i].r);
                float dg = Math.abs(got[i].g - exp[i].g);
                float db = Math.abs(got[i].b - exp[i].b);
                assertTrue("Bar " + i + " terlalu berbeda R:" + dr + " G:" + dg + " B:" + db,
                        dr < 40 && dg < 40 && db < 40);
            }
        }
    }
}


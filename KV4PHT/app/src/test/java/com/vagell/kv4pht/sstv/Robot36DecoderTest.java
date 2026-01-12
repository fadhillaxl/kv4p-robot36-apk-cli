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

public class Robot36DecoderTest {

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
        if (b.getInt(0) != 0x46464952) throw new IOException("Not RIFF"); // 'RIFF'
        if (b.getInt(8) != 0x45564157) throw new IOException("Not WAVE"); // 'WAVE'
        int fmtIdx = 12;
        // find 'fmt ' chunk
        while (fmtIdx + 8 <= hdr.length) {
            int id = b.getInt(fmtIdx);
            int len = b.getInt(fmtIdx + 4);
            if (id == 0x20746D66) break; // 'fmt '
            fmtIdx += 8 + len;
        }
        int audioFormat = b.getShort(fmtIdx + 8) & 0xffff;
        int channels = b.getShort(fmtIdx + 10) & 0xffff;
        int sampleRate = b.getInt(fmtIdx + 12);
        int bitsPerSample = b.getShort(fmtIdx + 22) & 0xffff;
        // find 'data' chunk
        long pos = 12;
        while (true) {
            fis.getChannel().position(pos);
            byte[] chunkHdr = new byte[8];
            if (fis.read(chunkHdr) < 8) throw new IOException("No data chunk");
            ByteBuffer ch = ByteBuffer.wrap(chunkHdr).order(ByteOrder.LITTLE_ENDIAN);
            int id = ch.getInt(0);
            int len = ch.getInt(4);
            if (id == 0x61746164) { // 'data'
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

    private float[] readFrame(FileInputStream fis, WavInfo info, int samples) throws IOException {
        int bytesPerSample = info.bitsPerSample / 8;
        int frameBytes = samples * bytesPerSample * info.channels;
        byte[] buf = new byte[frameBytes];
        int n = fis.read(buf);
        if (n <= 0) return null;
        int count = n / (bytesPerSample * info.channels);
        float[] out = new float[count];
        ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            if (bytesPerSample == 2) {
                short s = b.getShort(i * bytesPerSample * info.channels);
                out[i] = s / 32768f;
            } else if (bytesPerSample == 1) {
                int u = buf[i * info.channels] & 0xff;
                out[i] = (u - 128) / 128f;
            } else if (bytesPerSample == 4) {
                int v = b.getInt(i * bytesPerSample * info.channels);
                out[i] = Math.max(-1f, Math.min(1f, v / (float)Integer.MAX_VALUE));
            }
        }
        return out;
    }

    @Test
    public void decodeRobot36Wav() throws Exception {
        File f = new File("/Users/mm/GitHub/kv4p-robot36-apk/KV4PHT/sstv-Robot36-20251116-092211.wav");
        try (FileInputStream fis = new FileInputStream(f)) {
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
            // Expect image lines decoded
            assertTrue(image.line > 0);
            // Optionally write PNG
            int h = Math.min(image.line, image.height);
            java.awt.image.BufferedImage png = new java.awt.image.BufferedImage(image.width, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            png.setRGB(0, 0, image.width, h, image.pixels, 0, image.width);
            File out = new File("KV4PHT/app/build/robot36-test.png");
            out.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(png, "PNG", out);
        }
    }
}
package com.vagell.kv4pht.ui;

import android.Manifest;
import android.graphics.Bitmap;
import android.media.audiofx.Visualizer;
import android.media.AudioRecord;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.vagell.kv4pht.R;
import com.vagell.kv4pht.data.AppSetting;
import com.vagell.kv4pht.radio.RadioAudioService;
import com.vagell.kv4pht.sstv.robot36.Decoder;
import com.vagell.kv4pht.sstv.robot36.PixelBuffer;
import com.vagell.kv4pht.sstv.robot36.ShortTimeFourierTransform;
import com.vagell.kv4pht.sstv.robot36.AudioResampler;
import com.vagell.kv4pht.sstv.robot36.Complex;

public class Robot36Activity extends AppCompatActivity {
    private MainViewModel viewModel;
    private RadioAudioService radioAudioService;
    private Visualizer visualizer;
    private PixelBuffer scopeBuffer;
    private PixelBuffer waterfallBuffer;
    private PixelBuffer peakBuffer;
    private PixelBuffer imageBuffer;
    private Bitmap scopeBitmap;
    private Bitmap waterfallBitmap;
    private Bitmap peakBitmap;
    private Bitmap imageBitmap;
    private ImageView scopeView;
    private ImageView waterfallView;
    private ImageView peakView;
    private ImageView imageView;
    private Decoder decoder;
    private Decoder decoderRadio;
    private AudioResampler radioResampler;
    private ShortTimeFourierTransform stft;
    private final Complex stftInput = new Complex();
    private boolean paused = false;
    private boolean usingFile = false;
    private boolean showMeters = true;
    private boolean resampleRadioOn = true;
    private static final int REQUEST_OPEN_WAV = 7001;
    private AudioRecord micRecord;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_robot36);
        viewModel = new androidx.lifecycle.ViewModelProvider(this).get(MainViewModel.class);
        scopeBuffer = new PixelBuffer(640, 2 * 1280);
        waterfallBuffer = new PixelBuffer(256, 2 * 256);
        peakBuffer = new PixelBuffer(1, 16);
        imageBuffer = new PixelBuffer(800, 616);
        scopeView = findViewById(R.id.scope);
        waterfallView = findViewById(R.id.waterfall_plot);
        peakView = findViewById(R.id.peak_meter);
        imageView = findViewById(R.id.image_preview);
        scopeBitmap = Bitmap.createBitmap(scopeBuffer.width, scopeBuffer.height / 2, Bitmap.Config.ARGB_8888);
        waterfallBitmap = Bitmap.createBitmap(waterfallBuffer.width, waterfallBuffer.height / 2, Bitmap.Config.ARGB_8888);
        peakBitmap = Bitmap.createBitmap(peakBuffer.width, peakBuffer.height, Bitmap.Config.ARGB_8888);
        imageBitmap = Bitmap.createBitmap(imageBuffer.width, imageBuffer.height, Bitmap.Config.ARGB_8888);
        scopeView.setImageBitmap(scopeBitmap);
        waterfallView.setImageBitmap(waterfallBitmap);
        peakView.setImageBitmap(peakBitmap);
        imageView.setImageBitmap(imageBitmap);
        imageView.setScaleType(android.widget.ImageView.ScaleType.FIT_START);
        decoder = new Decoder(scopeBuffer, imageBuffer, "Raw", RadioAudioService.AUDIO_SAMPLE_RATE);
        decoderRadio = new Decoder(scopeBuffer, imageBuffer, "Raw", 11025);
        decoderRadio.setMode("Robot 36 Color");
        radioResampler = new AudioResampler(RadioAudioService.AUDIO_SAMPLE_RATE, 11025);
        stft = new ShortTimeFourierTransform(RadioAudioService.AUDIO_SAMPLE_RATE / 10, 3);
        initModeDropdown();
        startDecodingBySource();
        updateMetersVisibility();

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_pause) {
                paused = !paused;
                item.setIcon(paused ? R.drawable.baseline_play_arrow_24 : R.drawable.baseline_pause_24);
                item.setTitle(paused ? "Resume" : "Pause");
                return true;
            } else if (id == R.id.action_save) {
                saveClicked(toolbar);
                return true;
            } else if (id == R.id.action_share) {
                shareClicked(toolbar);
                return true;
            } else if (id == R.id.action_open) {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("audio/*");
                startActivityForResult(pick, REQUEST_OPEN_WAV);
                return true;
            } else if (id == R.id.action_mode) {
                String[] modes = new String[]{"Auto","Raw","Robot 36 Color","Robot 72 Color","Scottie 1","Scottie 2","Scottie DX","Martin 1","Martin 2","Wraase SC2–180","HF Fax","PD 50","PD 90","PD 120","PD 160","PD 180","PD 240","PD 290"};
                new androidx.appcompat.app.AlertDialog.Builder(Robot36Activity.this)
                        .setTitle("Lock Mode")
                        .setItems(modes, (d, which) -> {
                            String name = modes[which];
                            decoder.setMode(name);
                            if (decoderRadio != null) decoderRadio.setMode(name);
                            TextView status = findViewById(R.id.status);
                            if (status != null) {
                                CharSequence src = status.getText();
                                status.setText((src != null ? src.toString() : "") + " | Mode: " + name);
                            }
                        })
                        .show();
                return true;
            } else if (id == R.id.action_visuals) {
                showMeters = !showMeters;
                item.setIcon(showMeters ? R.drawable.baseline_visibility_off_24 : R.drawable.baseline_visibility_24);
                updateMetersVisibility();
                return true;
            } else if (id == R.id.action_close) {
                finish();
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPEN_WAV && resultCode == RESULT_OK && data != null && data.getData() != null) {
            decodeWavUri(data.getData());
        }
    }

    private void decodeWavUri(android.net.Uri uri) {
        new Thread(() -> {
            TextView status = findViewById(R.id.status);
            try {
                usingFile = true;
                try { if (visualizer != null) { visualizer.setEnabled(false); visualizer.release(); visualizer = null; } } catch (Throwable ignored) {}
                try { if (micRecord != null) { micRecord.stop(); micRecord.release(); micRecord = null; } } catch (Throwable ignored) {}
                if (status!=null) runOnUiThread(() -> status.setText("Decoding file..."));
                java.io.InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) { if (status!=null) runOnUiThread(() -> status.setText("Failed to open file")); return; }
                byte[] head12 = readExact(is, 12);
                if (head12 == null) { if (status!=null) runOnUiThread(() -> status.setText("Invalid WAV")); return; }
                java.nio.ByteBuffer b12 = java.nio.ByteBuffer.wrap(head12).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                int riff = b12.getInt(0);
                int wave = b12.getInt(8);
                if (riff != 0x46464952 || wave != 0x45564157) { if (status!=null) runOnUiThread(() -> status.setText("Not RIFF/WAVE")); return; }
                int sampleRate = 0, channels = 1, bitsPerSample = 16;
                long dataLen = 0;
                // iterate chunks
                while (true) {
                    byte[] chdr = readExact(is, 8);
                    if (chdr == null) break;
                    java.nio.ByteBuffer ch = java.nio.ByteBuffer.wrap(chdr).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    int id = ch.getInt(0);
                    int len = ch.getInt(4);
                    if (id == 0x20746D66) { // 'fmt '
                        byte[] fmt = readExact(is, len);
                        if (fmt == null) break;
                        java.nio.ByteBuffer bf = java.nio.ByteBuffer.wrap(fmt).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        int audioFormat = bf.getShort(0) & 0xffff; // 1=PCM, 3=float
                        channels = bf.getShort(2) & 0xffff;
                        sampleRate = bf.getInt(4);
                        bitsPerSample = bf.getShort(14) & 0xffff;
                        if (audioFormat != 1 && audioFormat != 3) { if (status!=null) runOnUiThread(() -> status.setText("Unsupported WAV format")); return; }
                    } else if (id == 0x61746164) { // 'data'
                        dataLen = len;
                        break;
                    } else {
                        skipExact(is, len);
                    }
                }
                if (dataLen <= 0 || sampleRate <= 0) { if (status!=null) runOnUiThread(() -> status.setText("Missing data/fmt")); return; }
                int bytesPerSample = Math.max(1, bitsPerSample / 8);
                int stride = bytesPerSample * channels;
                PixelBuffer scope = new PixelBuffer(640, 2 * 1280);
                PixelBuffer image = new PixelBuffer(800, 616);
                Decoder dec = new Decoder(scope, image, "Raw", sampleRate);
                int hop = Math.max(512, sampleRate / 100);
                byte[] buf = new byte[stride * hop];
                long remaining = dataLen;
                while (remaining > 0) {
                    int need = (int) Math.min(buf.length, remaining);
                    int n = is.read(buf, 0, need);
                    if (n <= 0) break;
                    remaining -= n;
                    int frames = n / stride;
                    float[] frame = new float[frames];
                    java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf, 0, frames * stride).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < frames; i++) {
                        int p = i * stride;
                        float sample;
                        if (bytesPerSample == 2) sample = bb.getShort(p) / 32768f;
                        else if (bytesPerSample == 1) sample = ((buf[p] & 0xff) - 128) / 128f;
                        else if (bytesPerSample == 4) sample = Math.max(-1f, Math.min(1f, bb.getInt(p) / (float)Integer.MAX_VALUE));
                        else sample = 0;
                        frame[i] = sample;
                    }
                    // normalize gain to help detection
                    float max = 0f; for (float v : frame) max = Math.max(max, Math.abs(v));
                    if (max > 0) { float g = Math.min(3f, 0.6f / max); for (int i=0;i<frame.length;i++) frame[i]*=g; }
                    boolean newLines = dec.process(frame, 0);
                    float[] copy = java.util.Arrays.copyOf(frame, frame.length);
                    runOnUiThread(() -> { updatePeak(copy); updateWaterfall(copy); if (newLines) { updateScope(); updateImagePreview(); } });
                }
                runOnUiThread(() -> { updateScope(); updateImagePreview(); if (status!=null) status.setText("Decode finished"); });
            } catch (Exception e) {
                if (status!=null) runOnUiThread(() -> status.setText("Decode error"));
            } finally { usingFile = false; }
        }).start();
    }

    private byte[] readExact(java.io.InputStream is, int len) throws java.io.IOException {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = is.read(b, off, len - off);
            if (n <= 0) return null;
            off += n;
        }
        return b;
    }

    private void skipExact(java.io.InputStream is, int len) throws java.io.IOException {
        long rem = len;
        while (rem > 0) {
            long s = is.skip(rem);
            if (s <= 0) { if (is.read() < 0) break; else s = 1; }
            rem -= s;
        }
    }

    private void attachVisualizer() {
        if (usingFile) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1001);
            return;
        }
        com.vagell.kv4pht.radio.RadioServiceConnector connector = new com.vagell.kv4pht.radio.RadioServiceConnector(this);
        connector.bind(service -> {
            service.setCallbacks(new com.vagell.kv4pht.radio.RadioAudioService.RadioAudioServiceCallbacks() {
                @Override public void audioTrackCreated() { attachVisualizer(); }
                @Override public void rxAudio(float[] samples, int length) {
                    if (paused || usingFile) return;
                    float[] feed = radioResampler != null ? radioResampler.process(samples) : samples;
                    boolean newLines = decoderRadio != null ? decoderRadio.process(feed, 0) : decoder.process(samples, 0);
                    updatePeak(samples);
                    updateWaterfall(samples);
                    if (newLines) { updateScope(); updateImagePreview(); }
                }
            });
            int session = service.getAudioTrackSessionId();
            if (session <= 0) {
                TextView status = findViewById(R.id.status);
                if (status != null) status.setText("Menunggu audio RX…");
                return;
            }
            try {
                if (visualizer != null) { visualizer.setEnabled(false); visualizer.release(); }
                visualizer = new Visualizer(session);
                visualizer.setEnabled(false);
                int size = Visualizer.getCaptureSizeRange()[1];
                visualizer.setCaptureSize(size);
                visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                    @Override
                    public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {
                        if (paused) return;
                        float[] buf = new float[waveform.length];
                        for (int i = 0; i < waveform.length; i++) buf[i] = (waveform[i] - 128f) / 128f;
                        float[] feed = radioResampler != null ? radioResampler.process(buf) : buf;
                        boolean newLines = decoderRadio != null ? decoderRadio.process(feed, 0) : decoder.process(buf, 0);
                        updatePeak(buf);
                        updateWaterfall(buf);
                        if (newLines) { updateScope(); updateImagePreview(); }
                    }
                    @Override
                    public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {}
                }, Visualizer.getMaxCaptureRate(), true, false);
                visualizer.setEnabled(true);
            } catch (Exception e) {
                TextView status = findViewById(R.id.status);
                if (status != null) status.setText("Visualizer unavailable: use Mic or Open file");
            }
        });
    }

    private void updateScope() {
        int stride = scopeBuffer.width;
        int height = scopeBuffer.height / 2;
        int offset = stride * (scopeBuffer.line + scopeBuffer.height / 2 - height);
        scopeBitmap.setPixels(scopeBuffer.pixels, offset, stride, 0, 0, stride, height);
        scopeView.invalidate();
        View sv = findViewById(R.id.scopeScroll);
        if (sv instanceof android.widget.ScrollView) {
            sv.post(() -> ((android.widget.ScrollView) sv).fullScroll(View.FOCUS_DOWN));
        }
    }

    private void updateImagePreview() {
        try {
            if (imageBuffer.width <= 0 || imageBuffer.height <= 0 || imageBuffer.line < 0) return;
            if (imageBitmap.getWidth() != imageBuffer.width || imageBitmap.getHeight() != imageBuffer.height) {
                imageBitmap = Bitmap.createBitmap(imageBuffer.width, imageBuffer.height, Bitmap.Config.ARGB_8888);
                imageView.setImageBitmap(imageBitmap);
            }
            int y = Math.max(0, Math.min(imageBuffer.line, imageBuffer.height));
            imageBitmap.setPixels(imageBuffer.pixels, 0, imageBuffer.width, 0, 0, imageBuffer.width, y);
            imageView.invalidate();
        } catch (Exception ignored) {}
    }

    private void updateMetersVisibility() {
        View scope = findViewById(R.id.scopeScroll);
        View waterfall = findViewById(R.id.waterfall_plot);
        View peak = findViewById(R.id.peak_meter);
        scope.setVisibility(showMeters ? View.VISIBLE : View.GONE);
        waterfall.setVisibility(showMeters ? View.VISIBLE : View.GONE);
        peak.setVisibility(showMeters ? View.VISIBLE : View.GONE);
        imageView.setScaleType(showMeters ? android.widget.ImageView.ScaleType.FIT_CENTER : android.widget.ImageView.ScaleType.FIT_START);
    }

    private void updatePeak(float[] buffer) {
        float max = 0;
        for (float v : buffer) max = Math.max(max, Math.abs(v));
        int pixels = peakBuffer.height;
        int peak = pixels;
        if (max > 0) peak = (int) Math.round(Math.min(Math.max(-Math.PI * Math.log(max), 0), pixels));
        java.util.Arrays.fill(peakBuffer.pixels, 0, peak, 0x55000000);
        java.util.Arrays.fill(peakBuffer.pixels, peak, pixels, 0xff00ff00);
        peakBitmap.setPixels(peakBuffer.pixels, 0, peakBuffer.width, 0, 0, peakBuffer.width, peakBuffer.height);
        peakView.invalidate();
    }

    private void updateWaterfall(float[] buffer) {
        boolean process = false;
        for (int j = 0; j < buffer.length; ++j) {
            stftInput.set(buffer[j]);
            if (stft.push(stftInput)) {
                process = true;
                int stride = waterfallBuffer.width;
                waterfallBuffer.line = (waterfallBuffer.line + waterfallBuffer.height / 2 - 1) % (waterfallBuffer.height / 2);
                int line = stride * waterfallBuffer.line;
                double lowest = Math.log(1e-9);
                double highest = Math.log(1);
                double range = highest - lowest;
                int minFreq = 140;
                int minBin = minFreq / 10;
                for (int i = 0; i < stride; ++i) {
                    int color = rainbow((Math.log(stft.power[i + minBin]) - lowest) / range);
                    waterfallBuffer.pixels[line + i] = color;
                }
                System.arraycopy(waterfallBuffer.pixels, line, waterfallBuffer.pixels, line + stride * (waterfallBuffer.height / 2), stride);
            }
        }
        if (process) {
            int stride = waterfallBuffer.width;
            int offset = stride * waterfallBuffer.line;
            waterfallBitmap.setPixels(waterfallBuffer.pixels, offset, stride, 0, 0, waterfallBuffer.width, waterfallBuffer.height / 2);
            waterfallView.invalidate();
        }
    }

    private int rainbow(double v) {
        v = Math.min(Math.max(v, 0), 1);
        double t = 4 * v - 2;
        return argb(4 * v, t, 1 - Math.abs(t), -t);
    }

    private int argb(double a, double r, double g, double b) {
        a = clamp(a); r = clamp(r); g = clamp(g); b = clamp(b);
        r *= a; g *= a; b *= a;
        r = Math.sqrt(r); g = Math.sqrt(g); b = Math.sqrt(b);
        int A = (int) Math.rint(255 * a);
        int R = (int) Math.rint(255 * r);
        int G = (int) Math.rint(255 * g);
        int B = (int) Math.rint(255 * b);
        return (A << 24) | (R << 16) | (G << 8) | B;
    }

    private double clamp(double x) { return Math.min(Math.max(x, 0), 1); }

    public void backClicked(View v) { finish(); }

    public void saveClicked(View v) {
        try {
            if (imageBuffer != null && imageBuffer.width > 0 && imageBuffer.height > 0 && imageBuffer.line >= imageBuffer.height) {
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(imageBuffer.pixels, imageBuffer.width, imageBuffer.height, android.graphics.Bitmap.Config.ARGB_8888);
                saveBitmap(bmp);
                return;
            }
            int width = scopeBuffer.width;
            int height = scopeBuffer.height / 2;
            int stride = scopeBuffer.width;
            int offset = stride * (scopeBuffer.line + scopeBuffer.height / 2 - height);
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(scopeBuffer.pixels, offset, stride, width, height, android.graphics.Bitmap.Config.ARGB_8888);
            saveBitmap(bmp);
        } catch (Exception ignored) { }
    }

    private void saveBitmap(android.graphics.Bitmap bmp) {
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US);
            String name = "sstv-Robot36-" + fmt.format(new java.util.Date()) + ".png";
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures");
            android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                if (os != null) { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os); os.close(); }
                android.widget.Toast.makeText(this, "Saved to Pictures", android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) { }
    }

    public void shareClicked(View v) {
        try {
            android.graphics.Bitmap bmp;
            if (imageBuffer != null && imageBuffer.width > 0 && imageBuffer.height > 0 && imageBuffer.line > 0) {
                int y = Math.min(imageBuffer.line, imageBuffer.height);
                bmp = android.graphics.Bitmap.createBitmap(imageBuffer.width, y, android.graphics.Bitmap.Config.ARGB_8888);
                bmp.setPixels(imageBuffer.pixels, 0, imageBuffer.width, 0, 0, imageBuffer.width, y);
            } else {
                int width = scopeBuffer.width;
                int height = scopeBuffer.height / 2;
                int stride = scopeBuffer.width;
                int offset = stride * (scopeBuffer.line + scopeBuffer.height / 2 - height);
                bmp = android.graphics.Bitmap.createBitmap(scopeBuffer.pixels, offset, stride, width, height, android.graphics.Bitmap.Config.ARGB_8888);
            }
            java.io.File cache = new java.io.File(getCacheDir(), "share.png");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(cache);
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName()+".fileprovider", cache);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("image/png");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share SSTV"));
        } catch (Exception ignored) {}
    }

    public void pauseClicked(View v) {
        paused = !paused;
        ((android.widget.Button)v).setText(paused ? "Resume" : "Pause");
    }

    private void initModeDropdown() {
        android.widget.AutoCompleteTextView modeView = findViewById(R.id.modeTextView);
        java.util.List<String> modes = java.util.Arrays.asList("Auto","Raw","Robot 36 Color","Robot 72 Color","Scottie 1","Scottie 2","Scottie DX","Martin 1","Martin 2","Wraase SC2–180","HF Fax","PD 50","PD 90","PD 120","PD 160","PD 180","PD 240","PD 290");
        modeView.setAdapter(new android.widget.ArrayAdapter<>(this, R.layout.dropdown_item, modes));
        modeView.setOnItemClickListener((parent, view, position, id) -> {
            String name = modes.get(position);
            decoder.setMode(name);
            if (decoderRadio != null) decoderRadio.setMode(name);
            TextView status = findViewById(R.id.status);
            if (status != null) {
                String src = status.getText() != null ? status.getText().toString() : "";
                status.setText(src + " | Mode: " + name);
            }
        });
    }

    private void startDecodingBySource() {
        new Thread(() -> {
            try {
                java.util.Map<String, String> settings = viewModel.getAppDb().appSettingDao().getAll().stream()
                        .collect(java.util.stream.Collectors.toMap(AppSetting::getName, AppSetting::getValue));
                String src = settings.get(AppSetting.SETTING_SSTV_AUDIO_SOURCE);
                String res = settings.get(AppSetting.SETTING_SSTV_RESAMPLE_RADIO);
                runOnUiThread(() -> {
                    resampleRadioOn = res == null || res.equalsIgnoreCase("On") || res.equalsIgnoreCase("True");
                    if (src != null && (src.equalsIgnoreCase("Mic") || src.equalsIgnoreCase("Microphone"))) {
                        startMicDecoding();
                    } else {
                        attachRadioCallbacks();
                        TextView status = findViewById(R.id.status);
                        if (status != null) status.setText(resampleRadioOn ? "Source: Radio (48k→11.025k, LPF)" : "Source: Radio (48k)");
                    }
                });
            } catch (Throwable ignored) {
                runOnUiThread(this::attachRadioCallbacks);
            }
        }).start();
    }

    private void attachRadioCallbacks() {
        com.vagell.kv4pht.radio.RadioServiceConnector connector = new com.vagell.kv4pht.radio.RadioServiceConnector(this);
        connector.bind(service -> {
            service.setCallbacks(new com.vagell.kv4pht.radio.RadioAudioService.RadioAudioServiceCallbacks() {
                @Override public void rxAudio(float[] samples, int length) {
                    if (paused || usingFile) return;
                    float[] feed = (radioResampler != null && resampleRadioOn) ? radioResampler.process(samples) : samples;
                    boolean newLines = decoderRadio.process(feed, 0);
                    if (newLines) { updateScope(); updateImagePreview(); }
                    updatePeak(samples);
                    updateWaterfall(samples);
                }
            });
        });
    }

    private void startMicDecoding() {
        try {
            if (micRecord != null) { micRecord.stop(); micRecord.release(); micRecord = null; }
            int sr = RadioAudioService.AUDIO_SAMPLE_RATE;
            int ch = android.media.AudioFormat.CHANNEL_IN_MONO;
            int fmt = android.media.AudioFormat.ENCODING_PCM_FLOAT;
            micRecord = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, sr, ch, fmt,
                    android.media.AudioRecord.getMinBufferSize(sr, ch, fmt));
            micRecord.startRecording();
            new Thread(() -> {
                float[] buf = new float[RadioAudioService.OPUS_FRAME_SIZE];
                while (micRecord != null) {
                    if (paused) { try { Thread.sleep(50); } catch (InterruptedException ignored) {} continue; }
                    int n = micRecord.read(buf, 0, buf.length, android.media.AudioRecord.READ_BLOCKING);
                    if (n <= 0) break;
                    boolean newLines = decoder.process(buf, 0);
                    updatePeak(buf);
                    updateWaterfall(buf);
                    if (newLines) { updateScope(); updateImagePreview(); }
                }
            }).start();
            TextView status = findViewById(R.id.status);
            if (status != null) status.setText("Mic source active");
        } catch (Exception e) {
            TextView status = findViewById(R.id.status);
            if (status != null) status.setText("Mic error");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (visualizer != null) { visualizer.setEnabled(false); visualizer.release(); } } catch (Throwable ignored) {}
        try { if (micRecord != null) { micRecord.stop(); micRecord.release(); micRecord = null; } } catch (Throwable ignored) {}
    }
}
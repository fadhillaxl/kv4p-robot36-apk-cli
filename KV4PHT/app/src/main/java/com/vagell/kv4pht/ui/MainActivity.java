/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.vagell.kv4pht.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.usb.UsbManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.provider.MediaStore;
import android.content.ContentValues;
import java.io.OutputStream;
import android.os.Environment;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.net.Uri;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.vagell.kv4pht.BR;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.aprs.parser.APRSPacket;
import com.vagell.kv4pht.aprs.parser.APRSTypes;
import com.vagell.kv4pht.aprs.parser.InformationField;
import com.vagell.kv4pht.aprs.parser.MessagePacket;
import com.vagell.kv4pht.aprs.parser.ObjectField;
import com.vagell.kv4pht.aprs.parser.PositionField;
import com.vagell.kv4pht.aprs.parser.Utilities;
import com.vagell.kv4pht.aprs.parser.WeatherField;
import com.vagell.kv4pht.data.APRSMessage;
import com.vagell.kv4pht.data.AppSetting;
import com.vagell.kv4pht.data.ChannelMemory;
import com.vagell.kv4pht.databinding.ActivityMainBinding;
import com.vagell.kv4pht.radio.RadioAudioService;
import com.vagell.kv4pht.radio.RadioMode;
import com.vagell.kv4pht.radio.sstv.NativeSSTV;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.google.android.material.snackbar.Snackbar.LENGTH_LONG;
import static com.vagell.kv4pht.radio.RadioAudioService.INTENT_OPEN_CHAT;

public class MainActivity extends AppCompatActivity {
    // For transmitting audio to ESP32 / radio
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private int audioFormat = AudioFormat.ENCODING_PCM_FLOAT;
    private int minBufferSize = AudioRecord.getMinBufferSize(RadioAudioService.AUDIO_SAMPLE_RATE, channelConfig, audioFormat);

    private Thread recordingThread;

    private final Handler pttButtonDebounceHandler = new Handler(Looper.getMainLooper());

    // Active screen type (e.g. voice or chat)
    private ScreenType activeScreenType = ScreenType.SCREEN_VOICE;

    // Snackbars
    private Snackbar usbSnackbar = null;
    private Snackbar callsignSnackbar = null;
    private Snackbar versionSnackbar = null;
    private Snackbar radioModuleNotFoundSnackbar = null;

    // Android permission stuff
    private static final int REQUEST_AUDIO_PERMISSION_CODE = 1;
    private static final int REQUEST_NOTIFICATIONS_PERMISSION_CODE = 2;
    private static final int REQUEST_FINE_LOCATION_PERMISSION_CODE = 3;
    private static final int REQUEST_FOREGROUND_SERVICE_LOCATION_PERMISSION_CODE = 4;
    private static final String ACTION_USB_PERMISSION = "com.vagell.kv4pht.USB_PERMISSION";

    // Radio params and related settings
    private String activeFrequencyStr = "0.0000";
    private int squelch = 0;
    private String callsign = null;
    private boolean stickyPTT = false;
    private boolean disableAnimations = false;

    // Activity callback values
    public static final int REQUEST_ADD_MEMORY = 0;
    public static final int REQUEST_EDIT_MEMORY = 1;
    public static final int REQUEST_SETTINGS = 2;
    public static final int REQUEST_FIRMWARE = 3;
    public static final int REQUEST_FIND_REPEATERS = 4;
    public static final int REQUEST_SSTV_IMAGE = 5;

    private MainViewModel viewModel;
    private RecyclerView memoriesRecyclerView;
    private MemoriesAdapter memoriesAdapter;
    private RecyclerView aprsRecyclerView;
    private APRSAdapter aprsAdapter;

    private final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(2, 10, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

    private String selectedMemoryGroup = null; // null means unfiltered, no group selected
    private int activeMemoryId = -1; // -1 means we're in simplex mode
    private android.net.Uri sstvImageUri = null;
    private File sstvLastWavFile = null;
    private Visualizer sstvVisualizer;
    private AudioRecord sstvMicRecord;
    private com.vagell.kv4pht.radio.sstv.Robot36Decoder sstvDecoder;
    private Bitmap sstvScopeBitmap;
    private Canvas sstvScopeCanvas;
    private Paint sstvScopePaint;
    private int sstvTxSampleRate = RadioAudioService.AUDIO_SAMPLE_RATE;

    private enum SSTVInputSource { RADIO, MICROPHONE };
    private SSTVInputSource sstvInputSource = SSTVInputSource.RADIO;

    // Tx audio visualizer constants
    private static int MAX_AUDIO_VIZ_SIZE = 500;
    private static int MIN_TX_AUDIO_VIZ_SIZE = 200;
    private static int RECORD_ANIM_FPS = 30;

    // The main service that handles USB with the ESP32, incoming and outgoing audio, data, etc.
    private RadioAudioService radioAudioService = null;
    private boolean radioAudioServiceBound = false;

    // The firmware version of the kv4p HT radio device that's attached, or -1 if unknown.
    private int firmwareVersion = -1;
    private static final int EXPORT_WAV_SAMPLE_RATE = 11025;

    // This receiver will listen for a broadcast from the RadioAudioService when it's shutting down
    // (this is so that when the user swipes-away the kv4p HT notification, the app closes).
    private final BroadcastReceiver serviceShutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (RadioAudioService.ACTION_SERVICE_STOPPING.equals(intent.getAction())) {
                if (radioAudioServiceBound) {
                    unbindService(connection);
                    radioAudioServiceBound = false;
                }
                finish();
            }
        }
    };

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Bind data to the UI via the MainViewModel class
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        ActivityMainBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        binding.setLifecycleOwner(this);
        binding.setVariable(BR.viewModel, viewModel);

        // Prepare a RecyclerView for the list of channel memories
        memoriesRecyclerView = findViewById(R.id.memoriesList);
        memoriesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        memoriesAdapter = new MemoriesAdapter(new MemoriesAdapter.MemoryListener() {
            @Override
            public void onMemoryClick(ChannelMemory memory) {
                // Actually tune to it.
                if (radioAudioService != null && radioAudioService.getMode() == RadioMode.SCAN) {
                    radioAudioService.setScanning(false);
                    setScanningUi(false);
                }
                if (radioAudioService != null) {
                    radioAudioService.tuneToMemory(memory, squelch, false);
                    tuneToMemoryUi(memory.memoryId);
                }

                // Highlight the tapped memory, unhighlight all the others.
                viewModel.highlightMemory(memory);
                memoriesAdapter.notifyDataSetChanged();
            }

            @Override
            public void onMemoryDelete(ChannelMemory memory) {
                String freq = memory.frequency;
                viewModel.deleteMemoryAsync(memory, () -> viewModel.loadDataAsync(() -> runOnUiThread(() -> {
                    memoriesAdapter.notifyDataSetChanged();
                    if (radioAudioService != null) {
                        radioAudioService.tuneToFreq(freq, squelch, false); // Stay on the same freq as the now-deleted memory
                        tuneToFreqUi(freq, false);
                    }
                })));
            }

            @Override
            public void onMemoryEdit(ChannelMemory memory) {
                Intent intent = new Intent("com.vagell.kv4pht.EDIT_MEMORY_ACTION");
                intent.putExtra("requestCode", REQUEST_EDIT_MEMORY);
                intent.putExtra("memoryId", memory.memoryId);
                intent.putExtra("isVhfRadio", (radioAudioService != null && radioAudioService.getRadioType() == RadioAudioService.RadioModuleType.VHF));
                startActivityForResult(intent, REQUEST_EDIT_MEMORY);
            }
        });
        memoriesRecyclerView.setAdapter(memoriesAdapter);

        // Observe the channel memories LiveData in MainViewModel (so the RecyclerView can populate with the memories)
        viewModel.getChannelMemories().observe(this, new Observer<List<ChannelMemory>>() {
            @Override
            public void onChanged(List<ChannelMemory> channelMemories) {
                // Update the adapter's data
                if (selectedMemoryGroup != null) {
                    for (int i = 0; i < channelMemories.size(); i++) {
                        if (!channelMemories.get(i).group.equals(selectedMemoryGroup)) {
                            channelMemories.remove(i--);
                        }
                    }
                }
                memoriesAdapter.setMemoriesList(channelMemories);
                memoriesAdapter.notifyDataSetChanged();
            }
        });

        // Prepare a RecyclerView for the list APRS messages we've received in the past
        aprsRecyclerView = findViewById(R.id.aprsRecyclerView);
        aprsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        aprsAdapter = new APRSAdapter();
        aprsRecyclerView.setAdapter(aprsAdapter);

        // Observe the APRS messages LiveData in MainViewModel (so the RecyclerView can populate with the APRS messages)
        viewModel.getAPRSMessages().observe(this, new Observer<List<APRSMessage>>() {
            @Override
            public void onChanged(List<APRSMessage> aprsMessages) {
                aprsAdapter.setAPRSMessageList(aprsMessages);
                aprsAdapter.notifyDataSetChanged();

                // Scroll to the bottom when a new message is added
                if (aprsMessages != null && !aprsMessages.isEmpty()) {
                    aprsRecyclerView.scrollToPosition(aprsMessages.size() - 1);
                }
            }
        });

        // Set up behavior on the bottom nav
        BottomNavigationView bottomNav = findViewById(R.id.bottomNavigationView);
        bottomNav.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.voice_mode) {
                    showScreen(ScreenType.SCREEN_VOICE);
                } else if (itemId == R.id.text_chat_mode) {
                    showScreen(ScreenType.SCREEN_CHAT);
                } else if (itemId == R.id.sstv_mode) {
                    showScreen(ScreenType.SCREEN_SSTV);
                }
                return true;
            }
        });

        requestAudioPermissions();
        requestNotificationPermissions();
        requestForegroundServiceLocationPermissions();
        requestFinePositionPermissions();
        attachListeners();

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);
        registerReceiver(serviceShutdownReceiver, new IntentFilter(RadioAudioService.ACTION_SERVICE_STOPPING), ContextCompat.RECEIVER_NOT_EXPORTED);

        viewModel.loadDataAsync(this::applySettings);
    }

    private float[] resampleFrameTo48k(float[] in, int inRate) {
        int outRate = RadioAudioService.AUDIO_SAMPLE_RATE;
        int outLen = RadioAudioService.OPUS_FRAME_SIZE;
        float[] out = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            double pos = (double)i * inRate / (double)outRate;
            int idx = (int)pos;
            double frac = pos - idx;
            float s1 = in[Math.min(idx, in.length - 1)];
            float s2 = in[Math.min(idx + 1, in.length - 1)];
            out[i] = (float)(s1 + (s2 - s1) * frac);
        }
        return out;
    }

    private static class WavPCM { int sampleRate; float[] samples; WavPCM(int sr, float[] s){ this.sampleRate=sr; this.samples=s; } }

    private WavPCM loadWavMono16(File file) {
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
            byte[] hdr = new byte[12];
            raf.readFully(hdr);
            byte[] buf = new byte[8];
            int sr = 0; int channels = 0; int bps = 0; int dataSize = 0; long dataPos = -1;
            while (raf.getFilePointer() < raf.length()) {
                raf.readFully(buf);
                String id = new String(buf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
                int size = ((buf[4] & 0xff)) | ((buf[5] & 0xff) << 8) | ((buf[6] & 0xff) << 16) | ((buf[7] & 0xff) << 24);
                if ("fmt ".equals(id)) {
                    byte[] f = new byte[size]; raf.readFully(f);
                    int fmt = (f[0] & 0xff) | ((f[1] & 0xff) << 8);
                    channels = (f[2] & 0xff) | ((f[3] & 0xff) << 8);
                    sr = (f[4] & 0xff) | ((f[5] & 0xff) << 8) | ((f[6] & 0xff) << 16) | ((f[7] & 0xff) << 24);
                    bps = (f[14] & 0xff) | ((f[15] & 0xff) << 8);
                    if (fmt != 1) { raf.close(); return null; }
                } else if ("data".equals(id)) {
                    dataSize = size; dataPos = raf.getFilePointer(); raf.seek(dataPos + size); break;
                } else {
                    raf.seek(raf.getFilePointer() + size);
                }
            }
            if (dataPos < 0 || channels != 1 || bps != 16 || sr <= 0) { raf.close(); return null; }
            raf.seek(dataPos);
            byte[] pcm = new byte[dataSize]; raf.readFully(pcm); raf.close();
            int samples = dataSize / 2; float[] out = new float[samples];
            for (int i = 0; i < samples; i++) {
                int lo = pcm[i*2] & 0xff; int hi = pcm[i*2+1] & 0xff; short v = (short)((hi << 8) | lo);
                out[i] = Math.max(-1f, Math.min(1f, v / 32767.0f));
            }
            return new WavPCM(sr, out);
        } catch (Exception e) { return null; }
    }

    final MainActivity context = this;

    /** Defines callbacks for service binding, passed to bindService(). */
    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            radioAudioService = ((RadioAudioService.RadioBinder) service).getService();
            radioAudioServiceBound = true;

            // Give the service other critical things it needs to work properly.
            RadioAudioService.RadioAudioServiceCallbacks callbacks = new RadioAudioService.RadioAudioServiceCallbacks() {
                @Override
                public void radioMissing() {
                    showBand(BandType.BAND_UNKNOWN);
                    sMeterUpdate(0); // No rx when no radio
                    showUSBSnackbar();
                    findViewById(R.id.pttButton).setClickable(false);
                }

                @Override
                public void radioConnected() {
                    hideSnackBar();
                    applySettings();
                    findViewById(R.id.pttButton).setClickable(true);
                }

                @Override
                public void setRadioType(RadioAudioService.RadioModuleType radioType) {
                    if (radioType.equals(RadioAudioService.RadioModuleType.VHF)) {
                        showBand(BandType.BAND_VHF);
                    } else if (radioType.equals(RadioAudioService.RadioModuleType.UHF)) {
                        showBand(BandType.BAND_UHF);
                    } else {
                        showBand(BandType.BAND_UNKNOWN);
                    }
                }

                @Override
                public void hideSnackBar() {
                    if (usbSnackbar != null) {
                        usbSnackbar.dismiss();
                        usbSnackbar = null;
                    }
                    if (versionSnackbar != null) {
                        versionSnackbar.dismiss();
                        versionSnackbar = null;
                    }
                    if (radioModuleNotFoundSnackbar != null) {
                        radioModuleNotFoundSnackbar.dismiss();
                        radioModuleNotFoundSnackbar = null;
                    }

                }

                @Override
                public void radioModuleHandshake() {
                    showHandshakeSnackbar();
                }

                @Override
                public void radioModuleNotFound() {
                    showRadioModuleNotFoundSnackbar();
                }

                @Override
                public void audioTrackCreated() { }

                @Override
                public void packetReceived(APRSPacket aprsPacket) {
                    handleChatPacket(aprsPacket);
                }

                @Override
                public void scannedToMemory(int memoryId) {
                    tuneToMemoryUi(memoryId);
                }

                @Override
                public void outdatedFirmware(int firmwareVer) {
                    showVersionSnackbar(firmwareVer);
                }

                @Override
                public void firmwareVersionReceived(int firmwareVer) {
                    context.firmwareVersion = firmwareVer;
                }

                @Override
                public void missingFirmware() {
                    showVersionSnackbar(-1);
                }

                @Override
                public void txStarted() {
                    if (activeMemoryId == -1) {
                        return;
                    }

                    // Display any offset while transmitting.
                    List<ChannelMemory> channelMemories = viewModel.getChannelMemories().getValue();
                    if (channelMemories == null) {
                        return;
                    }
                    for (int i = 0; i < channelMemories.size(); i++) {
                        if (channelMemories.get(i).memoryId == activeMemoryId) {
                            ChannelMemory memory = channelMemories.get(i);
                            if (memory.offset == ChannelMemory.OFFSET_NONE) {
                                return; // No offset, can just leave current frequency visible.
                            }

                            Float freq = Float.parseFloat(memory.frequency);
                            freq = (memory.offset == ChannelMemory.OFFSET_UP) ? (freq + (0f + memory.offsetKhz / 1000f)) : (freq - (0f + memory.offsetKhz / 1000f));
                            showFrequency(radioAudioService.validateFrequency("" + freq));
                            break;
                        }
                    }
                }

                @Override
                public void txEnded() {
                    if (activeMemoryId == -1) {
                        return;
                    }

                    // Stop displaying any offset now that transmit is done.
                    List<ChannelMemory> channelMemories = viewModel.getChannelMemories().getValue();
                    if (channelMemories == null) {
                        return;
                    }
                    for (int i = 0; i < channelMemories.size(); i++) {
                        if (channelMemories.get(i).memoryId == activeMemoryId) {
                            ChannelMemory memory = channelMemories.get(i);
                            Float freq = Float.parseFloat(memory.frequency);
                            showFrequency(radioAudioService.validateFrequency("" + freq));
                            break;
                        }
                    }
                }

                @Override
                public void chatError(String text) {
                    Snackbar snackbar = Snackbar.make(context, findViewById(R.id.mainTopLevelLayout), text, LENGTH_LONG)
                            .setBackgroundTint(Color.rgb(140, 20, 0))
                            .setTextColor(Color.WHITE)
                            .setAnchorView(findViewById(R.id.textChatInput));

                    // Make the text of the snackbar larger.
                    TextView snackbarTextView = (TextView) snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
                    snackbarTextView.setTextSize(20);

                    snackbar.show();
                }

                @Override
                public void sMeterUpdate(int value) {
                    updateSMeter(value);
                }

                @Override
                public void aprsBeaconing(boolean beaconing, int accuracy) {
                    // If beaconing just started, let user know in case they didn't want this
                    // or forgot they turned it on. And warn them if they haven't set their callsign.
                    if (beaconing && (null == callsign || callsign.trim().length() == 0)) {
                        showCallsignSnackbar(getString(R.string.set_your_callsign_to_beacon_your_position));
                    } else if (beaconing) {
                        showBeaconingOnSnackbar(accuracy);
                    }
                }

                @Override
                public void sentAprsBeacon(double latitude, double longitude) {
                    // Show a mock-up of the beacon we sent, in our own chat log
                    APRSMessage myBeacon = new APRSMessage();
                    myBeacon.type = APRSMessage.POSITION_TYPE;
                    myBeacon.fromCallsign = callsign;
                    myBeacon.positionLat = latitude;
                    myBeacon.positionLong = longitude;
                    myBeacon.timestamp = java.time.Instant.now().getEpochSecond();
                    threadPoolExecutor.execute(() -> {
                        viewModel.getAppDb().aprsMessageDao().insertAll(myBeacon);
                        viewModel.loadDataAsync(() -> runOnUiThread(() -> aprsAdapter.notifyDataSetChanged()));
                    });

                    // Show a quick snackbar letting the user know we beaconed (so they can stop it if this is surprising)
                    showSimpleSnackbar("Transmitted APRS beacon on this frequency");
                }

                @Override
                public void unknownLocation() {
                    showSimpleSnackbar("Can't find your location, no beacon sent");
                }

                @Override
                public void forceTunedToFreq(String newFreqStr) {
                    // This is called when RadioAudioService is changing bands, and we need
                    // to reflect that in the UI.
                    tuneToFreqUi(newFreqStr, true);
                }

                @Override
                public void forcedPttStart() { // When user pushes physical PTT.
                    startPttUi(false);
                }

                @Override
                public void forcedPttEnd() { // When user releases physical PTT.
                    endPttUi();
                }

                @Override
                public void showNotification(String notificationChannelId, int notificationTypeId, String title, String message, String tapIntentName) {
                    doShowNotification(notificationChannelId, notificationTypeId, title, message, tapIntentName);
                }
            };

            radioAudioService.setCallbacks(callbacks);
            applySettings(); // Some settings require radioAudioService to exist to apply.
            radioAudioService.setChannelMemories(viewModel.getChannelMemories());
            runOnUiThread(() -> radioAudioService.start());
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            radioAudioService = null;
            radioAudioServiceBound = false;
            Log.d("DEBUG", "RadioAudioService disconnected from MainActivity.");
            // TODO if this is unexpected we should probably try to restart the service.
        }
    };

    @Override
    protected void onStart() {
        super.onStart();

        // Check necessary permissions, and if all is well, start RadioAudioService.
        tryToStartRadioAudioService();
    }

    private void tryToStartRadioAudioService() {
        // If it's already started, bail.
        if (null != radioAudioService && radioAudioServiceBound) {
            return;
        }

        // Do we have all the necessary permissions granted?
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestFinePositionPermissions();
            return;
        }

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermissions();
            return;
        }

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.FOREGROUND_SERVICE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestForegroundServiceLocationPermissions();
            return;
        }

        Intent intent = new Intent(this, RadioAudioService.class);
        intent.putExtra(AppSetting.SETTING_CALLSIGN, callsign);
        intent.putExtra(AppSetting.SETTING_SQUELCH, squelch);
        intent.putExtra("activeMemoryId", activeMemoryId);
        intent.putExtra("activeFrequencyStr", activeFrequencyStr);

        // Binding to the RadioAudioService causes it to start (e.g. play back audio).
        Intent serviceIntent = new Intent(this, RadioAudioService.class);
        ContextCompat.startForegroundService(this, serviceIntent); // Make it foreground (persistent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(serviceShutdownReceiver);
        try {
            threadPoolExecutor.shutdownNow();
        } catch (Exception ignored) { }

        try {
            if (radioAudioServiceBound) {
                unbindService(connection);
                radioAudioServiceBound = false;
            }
        } catch (Exception e) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.loadDataAsync(this::applySettings);
        // If we lost reference to the radioAudioService, re-establish it
        if (null == radioAudioService) {
            Intent intent = new Intent(this, RadioAudioService.class);
            intent.putExtra(AppSetting.SETTING_CALLSIGN, callsign);
            intent.putExtra(AppSetting.SETTING_SQUELCH, squelch);
            intent.putExtra("activeMemoryId", activeMemoryId);
            intent.putExtra("activeFrequencyStr", activeFrequencyStr);

            // Binding to the RadioAudioService causes it to start (e.g. play back audio).
            bindService(intent, connection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // If we arrived here from an APRS text chat notification, open text chat.
        if (intent != null && intent.getAction() != null && intent.getAction().equals(INTENT_OPEN_CHAT)) {
            showScreen(ScreenType.SCREEN_CHAT);
        }
    }

    private void handleChatPacket(APRSPacket aprsPacket) {
        // We use duck-typing for APRS messages since the spec is pretty loose with all the ways
        // you can define different fields and values. Once we know the type, we set aprsMessage.type.

        APRSMessage aprsMessage = new APRSMessage();
        InformationField infoField = aprsPacket.getPayload();
        WeatherField weatherField = (WeatherField) infoField.getAprsData(APRSTypes.T_WX);
        PositionField positionField = (PositionField) infoField.getAprsData(APRSTypes.T_POSITION);
        ObjectField objectField = (ObjectField) infoField.getAprsData(APRSTypes.T_OBJECT);
        aprsMessage.timestamp = java.time.Instant.now().getEpochSecond();

        // Get the fromCallsign (all APRS messages must have this)
        aprsMessage.fromCallsign = aprsPacket.getSourceCall();

        // Get the position, if included.
        if (null != positionField) {
            aprsMessage.type = APRSMessage.POSITION_TYPE; // Anything with a position is POSITION_TYPE unless we determine more specific type later.
            aprsMessage.positionLat = positionField.getPosition().getLatitude();
            aprsMessage.positionLong = positionField.getPosition().getLongitude();
        }

        // Try to find a comment (could be at multiple levels in the packet).
        String comment = aprsPacket.getComment();
        if (null != infoField && (null == comment || comment.trim().length() == 0)) {
            comment = infoField.getComment();
        }
        if (null != positionField && (null == comment || comment.trim().length() == 0)) {
            comment = positionField.getComment();
        }
        if (null != objectField && (null == comment || comment.trim().length() == 0)) {
            comment = objectField.getComment();
        }
        if (null != weatherField && (null == comment || comment.trim().length() == 0)) {
            comment = weatherField.getComment();
        }
        if (null != comment && comment.trim().length() > 0) {
            aprsMessage.comment = comment;
        }

        if (null != weatherField) { // APRS "weather" (i.e. any message with weather data attached)
            aprsMessage.type = APRSMessage.WEATHER_TYPE;
            aprsMessage.temperature = (null == weatherField.getTemp()) ? 0 : weatherField.getTemp();
            aprsMessage.humidity = (null == weatherField.getHumidity()) ? 0 : weatherField.getHumidity();
            aprsMessage.pressure = (null == weatherField.getPressure()) ? 0 : weatherField.getPressure();
            aprsMessage.rain = (null == weatherField.getRainLast24Hours()) ? 0 : weatherField.getRainLast24Hours(); // TODO don't ignore other rain measurements
            aprsMessage.snow = (null == weatherField.getSnowfallLast24Hours()) ? 0 : weatherField.getSnowfallLast24Hours();
            aprsMessage.windForce = (null == weatherField.getWindSpeed()) ? 0 : weatherField.getWindSpeed();
            aprsMessage.windDir = (null == weatherField.getWindDirection()) ? "" : Utilities.degressToCardinal(weatherField.getWindDirection());

            // Log.d("DEBUG", "Weather packet received");
        } else if (infoField.getDataTypeIdentifier() == ':') { // APRS "message" type. What we expect for our text chat.
            aprsMessage.type = APRSMessage.MESSAGE_TYPE;
            MessagePacket messagePacket = new MessagePacket(infoField.getRawBytes(), aprsPacket.getDestinationCall());
            aprsMessage.toCallsign = messagePacket.getTargetCallsign();

            if (messagePacket.isAck()) {
                aprsMessage.wasAcknowledged = true;
                try {
                    String msgNumStr = messagePacket.getMessageNumber();
                    if (msgNumStr != null) {
                        aprsMessage.msgNum = Integer.parseInt(msgNumStr.trim());
                    }
                } catch (Exception e) {
                    Log.d("DEBUG", "Warning: Bad message number in APRS ack, ignoring: '" + messagePacket.getMessageNumber() + "'");
                    e.printStackTrace();
                    return;
                }
                // Log.d("DEBUG", "Message ack received");
            } else {
                aprsMessage.msgBody = messagePacket.getMessageBody();
                // Log.d("DEBUG", "Message packet received");
            }
        } else if (infoField.getDataTypeIdentifier() == ';') { // APRS "object"
            aprsMessage.type = APRSMessage.OBJECT_TYPE;
            if (null != objectField) {
                aprsMessage.objName = objectField.getObjectName();
                // Log.d("DEBUG", "Object packet received");
            }
        }

        // If there is a fault in the packet, or the message type is unknown, we at least display the raw contents as a comment.
        if (aprsPacket.hasFault() || aprsMessage.type == APRSMessage.UNKNOWN_TYPE && (null == comment || comment.trim().length() == 0)) {
            if (null != infoField) {
                try {
                    comment = "Raw: " + new String(infoField.getRawBytes(), "UTF-8");
                    aprsMessage.comment = comment;
                } catch (Exception e) { }
            }
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                APRSMessage oldAPRSMessage = null;
                if (aprsMessage.wasAcknowledged) {
                    // When this is an ack, we don't insert anything in the DB, we try to find that old message to ack it.
                    oldAPRSMessage = viewModel.getAppDb().aprsMessageDao().getMsgToAck(aprsMessage.toCallsign, aprsMessage.msgNum);
                    if (null == oldAPRSMessage) {
                        Log.d("DEBUG", "Can't ack unknown APRS message from: " + aprsMessage.toCallsign + " with msg number: " + aprsMessage.msgNum);
                        return;
                    } else {
                        // Ack an old message
                        oldAPRSMessage.wasAcknowledged = true;
                        viewModel.getAppDb().aprsMessageDao().update(oldAPRSMessage);
                    }
                } else {
                    // Not an ack, add a message
                    viewModel.getAppDb().aprsMessageDao().insertAll(aprsMessage);
                }

                viewModel.loadDataAsync(() -> runOnUiThread(() -> aprsAdapter.notifyDataSetChanged()));
            }
        });
    }

    private enum ScreenType {
        SCREEN_VOICE,
        SCREEN_CHAT,
        SCREEN_SSTV
    };

    private void showScreen(ScreenType screenType) {
        // TODO The right way to implement the bottom nav toggling the UI would be with Fragments.
        // Controls for voice mode
        findViewById(R.id.voiceModeLineHolder).setVisibility(screenType == ScreenType.SCREEN_CHAT || screenType == ScreenType.SCREEN_SSTV ? GONE : VISIBLE);
        findViewById(R.id.pttButton).setVisibility(screenType == ScreenType.SCREEN_CHAT || screenType == ScreenType.SCREEN_SSTV ? GONE : VISIBLE);
        findViewById(R.id.memoriesList).setVisibility(screenType == ScreenType.SCREEN_CHAT || screenType == ScreenType.SCREEN_SSTV ? GONE : VISIBLE);
        findViewById(R.id.voiceModeBottomControls).setVisibility(screenType == ScreenType.SCREEN_CHAT || screenType == ScreenType.SCREEN_SSTV ? GONE : VISIBLE);

        findViewById(R.id.textModeContainer).setVisibility(screenType == ScreenType.SCREEN_CHAT ? VISIBLE : GONE);
        View sstvScroll = findViewById(R.id.sstvScroll);
        if (sstvScroll != null) sstvScroll.setVisibility(screenType == ScreenType.SCREEN_SSTV ? VISIBLE : GONE);

        if (screenType == ScreenType.SCREEN_CHAT) {
            stopSSTVDecoding();
            if (radioAudioService != null) {
                // Stop scanning when we enter chat mode, we don't want to tx data on an unexpected
                // frequency. User must set it manually (or select it before coming to chat mode, but
                // can't be scanning).
                radioAudioService.setScanning(false, true);
            }
            setScanningUi(false);

            // If their callsign is not set, display a snackbar asking them to set it before they
            // can transmit.
            if (callsign == null || callsign.length() == 0) {
                showCallsignSnackbar(getString(R.string.set_your_callsign_to_send_text_chat));
                ImageButton sendButton = findViewById(R.id.sendButton);
                sendButton.setEnabled(false);
                findViewById(R.id.sendButtonOverlay).setVisibility(VISIBLE);
            } else {
                ImageButton sendButton = findViewById(R.id.sendButton);
                sendButton.setEnabled(true);
                if (callsignSnackbar != null) {
                    callsignSnackbar.dismiss();
                }
                findViewById(R.id.sendButtonOverlay).setVisibility(GONE);
            }
        }
        else if (screenType == ScreenType.SCREEN_VOICE){
            stopSSTVDecoding();
            radioAudioService.setRssi(true);
            hideKeyboard();
            findViewById(R.id.frequencyContainer).setVisibility(VISIBLE);
            findViewById(R.id.rxAudioCircle).setVisibility(VISIBLE);

            if (callsignSnackbar != null) {
                callsignSnackbar.dismiss();
            }

            if (radioAudioService != null) {
                radioAudioService.setRssi(true);
                findViewById(R.id.sMeter).setVisibility(VISIBLE);
            }
        }
        else if (screenType == ScreenType.SCREEN_SSTV) {
//            hideKeyboard();
            showFrequency(activeFrequencyStr);
            View freqView = findViewById(R.id.frequencyContainer);
            if (freqView != null) freqView.setVisibility(VISIBLE);
            findViewById(R.id.textModeContainer).setVisibility(GONE);
            View sstv = findViewById(R.id.sstvScroll);
            if (sstv != null) sstv.setVisibility(VISIBLE);
            findViewById(R.id.rxAudioCircle).setVisibility(VISIBLE);
            findViewById(R.id.sMeter).setVisibility(VISIBLE);
            if (radioAudioService != null) {
                radioAudioService.setRssi(true);
                radioAudioService.setScanning(false, true);
            }
            setScanningUi(false);
            // Decoder dinonaktifkan: tidak otomatis memulai decoding di mode SSTV.
        }
        activeScreenType = screenType;
    }

    private void showCallsignSnackbar(CharSequence snackbarMsg) {
        callsignSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.set_now, new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        callsignSnackbar.dismiss();
                        startSettingsActivity();
                    }
                })
                .setBackgroundTint(getResources().getColor(R.color.primary))
                .setTextColor(getResources().getColor(R.color.medium_gray))
                .setActionTextColor(getResources().getColor(R.color.black))
                .setAnchorView(findViewById(R.id.bottomNavigationView));

        // Make the text of the snackbar larger.
        TextView snackbarActionTextView = (TextView) callsignSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = (TextView) callsignSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        callsignSnackbar.show();
    }

    private void showBeaconingOnSnackbar(int accuracy) {
        if (null != usbSnackbar && usbSnackbar.isShown()) { // No radio connected, that's more important.
            return;
        }

        String accuracyStr = (accuracy == RadioAudioService.APRS_POSITION_EXACT) ? getString(R.string.exact) : getString(R.string.approx);
        CharSequence snackbarMsg = getString(R.string.position_beacon_message_1) + accuracyStr + getString(R.string.position_beacon_message_2);
        Snackbar beaconingSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, LENGTH_LONG)
                .setAction("Settings", new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        startSettingsActivity();
                    }
                })
                .setBackgroundTint(getResources().getColor(R.color.primary))
                .setTextColor(getResources().getColor(R.color.medium_gray))
                .setActionTextColor(getResources().getColor(R.color.black))
                .setAnchorView(findViewById(R.id.bottomNavigationView));

        // Make the text of the snackbar larger.
        TextView snackbarActionTextView = (TextView) beaconingSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = (TextView) beaconingSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        beaconingSnackbar.show();
    }

    public void sendButtonOverlayClicked(View view) {
        if (callsign == null || callsign.length() == 0) {
            showCallsignSnackbar(getString(R.string.set_your_callsign_to_send_text_chat));
            ImageButton sendButton = findViewById(R.id.sendButton);
            sendButton.setEnabled(false);
        }
    }

    public void sendTextClicked(View view) {
        if (null != radioAudioService && !radioAudioService.isTxAllowed()) {
            showSimpleSnackbar(getString(R.string.can_t_tx_outside_ham_band));
            return;
        }

        String targetCallsign = ((EditText) findViewById(R.id.textChatTo)).getText().toString().trim();
        if (targetCallsign.length() == 0) {
            targetCallsign = "CQ";
        } else {
            targetCallsign = targetCallsign.toUpperCase();
        }
        ((EditText) findViewById(R.id.textChatTo)).setText(targetCallsign);

        String outText = ((EditText) findViewById(R.id.textChatInput)).getText().toString();
        if (outText.length() == 0) {
            return; // Nothing to send.
        }

        int msgNum = -1;
        if (radioAudioService != null) {
            msgNum = radioAudioService.sendChatMessage(targetCallsign, outText);
        }

        ((EditText) findViewById(R.id.textChatInput)).setText("");

        final APRSMessage aprsMessage = new APRSMessage();
        aprsMessage.type = APRSMessage.MESSAGE_TYPE;
        aprsMessage.fromCallsign = callsign.toUpperCase().trim();
        aprsMessage.toCallsign = targetCallsign.toUpperCase().trim();
        aprsMessage.msgBody = outText.trim();
        aprsMessage.timestamp = java.time.Instant.now().getEpochSecond();
        aprsMessage.msgNum = msgNum;

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                viewModel.getAppDb().aprsMessageDao().insertAll(aprsMessage);
                viewModel.loadDataAsync(() -> runOnUiThread(() -> aprsAdapter.notifyDataSetChanged()));
            }
        });

        findViewById(R.id.textChatInput).requestFocus();
    }

    private void updateRecordingVisualization(int waitMs, float txVolume) {
        if (disableAnimations) { return; }
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> runOnUiThread(() -> {
            ImageView txAudioView = findViewById(R.id.txAudioCircle);
            ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) txAudioView.getLayoutParams();
            RadioMode mode = radioAudioService != null ? radioAudioService.getMode() : RadioMode.UNKNOWN;
            layoutParams.width = Math.abs(txVolume) < 0.001 ||
                    mode == RadioMode.RX ? 0 : (int) (MAX_AUDIO_VIZ_SIZE * txVolume) + MIN_TX_AUDIO_VIZ_SIZE;
            layoutParams.height = Math.abs(txVolume) < 0.001 ||
                    mode == RadioMode.RX ? 0 : (int) (MAX_AUDIO_VIZ_SIZE * txVolume) + MIN_TX_AUDIO_VIZ_SIZE;
            txAudioView.setLayoutParams(layoutParams);
        }), waitMs); // waitMs gives us the fps we desire, see RECORD_ANIM_FPS constant.
    }

    private void applySettings() {
        if (!viewModel.isLoaded()) {
            return;
        }
        threadPoolExecutor.execute(() -> {
            final Map<String, String> settings = viewModel.getAppDb().appSettingDao().getAll().stream()
                .collect(Collectors.toMap(AppSetting::getName, AppSetting::getValue));
            runOnUiThread(() -> {
                applyRfPowerSetting(settings);
                applySquelchSettings(settings);
                applyCallSignSetting(settings);
                applyGroupAndMemorySettings(settings);
                applyTxFreqLimitsSettings(settings);
                applyBandwidthAndGainSettings(settings);
                applyFiltersSettings(settings);
                applyAccessibilitySettings(settings);
                applyAprsSettings(settings);
                applySstvAudioSourceSetting(settings);
            });
        });
    }

    private void applyRfPowerSetting(Map<String, String> settings) {
        List<String> powerOptions = Arrays.asList(getResources().getStringArray(R.array.rf_power_options));
        String power = settings.getOrDefault(AppSetting.SETTING_RF_POWER, powerOptions.get(0));
        if (radioAudioService != null) {
            radioAudioService.setHighPower(powerOptions.indexOf(power) == 0);
        }
    }

    private void applyCallSignSetting(Map<String, String> settings) {
        this.callsign = settings.getOrDefault(AppSetting.SETTING_CALLSIGN, "");
        boolean empty = callsign.isEmpty();
        findViewById(R.id.sendButton).setEnabled(!empty);
        findViewById(R.id.sendButtonOverlay).setVisibility(empty ? VISIBLE : GONE);
        if (radioAudioService != null) {
            radioAudioService.setCallsign(callsign);
        }
    }

    private void applyGroupAndMemorySettings(Map<String, String> settings) {
        String group = settings.get(AppSetting.SETTING_LAST_GROUP);
        if (group != null && !group.isEmpty()) {
            selectMemoryGroup(group);
        }
        String lastMemoryIdStr = settings.get(AppSetting.SETTING_LAST_MEMORY_ID);
        String lastFreq = settings.getOrDefault(AppSetting.SETTING_LAST_FREQ, "0.0000");
        activeMemoryId = (lastMemoryIdStr != null && !lastMemoryIdStr.equals("-1")) ? Integer.parseInt(lastMemoryIdStr) : -1;
        activeFrequencyStr = (activeMemoryId == -1) ? lastFreq : null;
        if (radioAudioService != null) {
            if (activeMemoryId > -1) {
                radioAudioService.setActiveMemoryId(activeMemoryId);
                radioAudioService.tuneToMemory(activeMemoryId, squelch, radioAudioService.getMode() == RadioMode.RX);
                tuneToMemoryUi(activeMemoryId);
            } else {
                radioAudioService.tuneToFreq(activeFrequencyStr, squelch, radioAudioService.getMode() == RadioMode.RX);
                tuneToFreqUi(activeFrequencyStr, radioAudioService.getMode() == RadioMode.RX);
            }
        }
    }

    private void applyTxFreqLimitsSettings(Map<String, String> settings) {
        if (radioAudioService == null) return;
        String min2m = settings.get(AppSetting.SETTING_MIN_2_M_TX_FREQ);
        String max2m = settings.get(AppSetting.SETTING_MAX_2_M_TX_FREQ);
        String min70 = settings.get(AppSetting.SETTING_MIN_70_CM_TX_FREQ);
        String max70 = settings.get(AppSetting.SETTING_MAX_70_CM_TX_FREQ);
        if (min2m != null) radioAudioService.setMin2mTxFreq(Integer.parseInt(min2m));
        if (max2m != null) radioAudioService.setMax2mTxFreq(Integer.parseInt(max2m));
        if (min70 != null) radioAudioService.setMin70cmTxFreq(Integer.parseInt(min70));
        if (max70 != null) radioAudioService.setMax70cmTxFreq(Integer.parseInt(max70));
        radioAudioService.updateFrequencyLimitsForBand();
    }

    private void applyBandwidthAndGainSettings(Map<String, String> settings) {
        if (radioAudioService == null) return;
        String bandwidth = settings.get(AppSetting.SETTING_BANDWIDTH);
        String gain = settings.get(AppSetting.SETTING_MIC_GAIN_BOOST);
        if (bandwidth != null) radioAudioService.setBandwidth(bandwidth);
        if (gain != null) radioAudioService.setMicGainBoost(gain);
    }

    private void applySquelchSettings(Map<String, String> settings) {
        String squelchStr = settings.get(AppSetting.SETTING_SQUELCH);
        if (squelchStr == null) return;
        squelch = Integer.parseInt(squelchStr);
        if (radioAudioService != null) {
            radioAudioService.setSquelch(squelch);
        }
    }

    private void applyFiltersSettings(Map<String, String> settings) {
        boolean emphasis = Boolean.parseBoolean(settings.getOrDefault(AppSetting.SETTING_EMPHASIS, "false"));
        boolean highpass = Boolean.parseBoolean(settings.getOrDefault(AppSetting.SETTING_HIGHPASS, "false"));
        boolean lowpass = Boolean.parseBoolean(settings.getOrDefault(AppSetting.SETTING_LOWPASS, "false"));
        if (radioAudioService != null && radioAudioService.isRadioConnected()) {
            threadPoolExecutor.execute(() -> {
                if (radioAudioService.getMode() != RadioMode.STARTUP && radioAudioService.getMode() != RadioMode.SCAN) {
                    radioAudioService.setMode(RadioMode.RX);
                    radioAudioService.setFilters(emphasis, highpass, lowpass);
                }
            });
        }
    }

    private void applyAccessibilitySettings(Map<String, String> settings) {
        disableAnimations = Boolean.parseBoolean(settings.getOrDefault(AppSetting.SETTING_DISABLE_ANIMATIONS, "false"));
        if (disableAnimations) {
            ImageView rxAudioView = findViewById(R.id.rxAudioCircle);
            ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) rxAudioView.getLayoutParams();
            layoutParams.width = 0;
            layoutParams.height = 0;
            rxAudioView.setLayoutParams(layoutParams);
            updateRecordingVisualization(100, 0.0f);
        }

        stickyPTT = Boolean.parseBoolean(settings.getOrDefault(AppSetting.SETTING_STICKY_PTT, "false"));
    }

    private void applyAprsSettings(Map<String, String> settings) {
        String accuracy = settings.get(AppSetting.SETTING_APRS_POSITION_ACCURACY);
        String beacon = settings.get(AppSetting.SETTING_APRS_BEACON_POSITION);

        if (accuracy != null && radioAudioService != null) {
            threadPoolExecutor.execute(() -> radioAudioService.setAprsPositionAccuracy(
                accuracy.equals(getString(R.string.exact)) ?
                    RadioAudioService.APRS_POSITION_EXACT :
                    RadioAudioService.APRS_POSITION_APPROX));
        }

        if (beacon != null && radioAudioService != null) {
            boolean beaconEnabled = Boolean.parseBoolean(beacon);
            threadPoolExecutor.execute(() -> radioAudioService.setAprsBeaconPosition(beaconEnabled));
            if (beaconEnabled) {
                requestFinePositionPermissions();
            }
        }
    }

    private void applySstvAudioSourceSetting(Map<String, String> settings) {
        String src = settings.get(AppSetting.SETTING_SSTV_AUDIO_SOURCE);
        if (src != null) {
            if ("Radio".equalsIgnoreCase(src)) {
                sstvInputSource = SSTVInputSource.RADIO;
            } else if ("Mic".equalsIgnoreCase(src) || "Microphone".equalsIgnoreCase(src)) {
                sstvInputSource = SSTVInputSource.MICROPHONE;
            }
            TextView status = findViewById(R.id.sstvSourceStatus);
            if (status != null) status.setText(getString(R.string.sstv_source_prefix) + " " + (sstvInputSource == SSTVInputSource.RADIO ? getString(R.string.sstv_source_radio) : getString(R.string.sstv_source_mic)));
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachListeners() {
        ImageButton pttButton = findViewById(R.id.pttButton);
        pttButton.setOnTouchListener((v, event) -> {
            boolean touchHandled = false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (!v.isClickable()) {
                        touchHandled = true;
                        break;
                    }

                    if (null != radioAudioService && !radioAudioService.isTxAllowed()) {
                        touchHandled = true;
                        showSimpleSnackbar(getString(R.string.can_t_tx_outside_ham_band));
                        break;
                    }

                    pttButtonDebounceHandler.removeCallbacksAndMessages(null);
                    if (stickyPTT) {
                        if (radioAudioService != null && radioAudioService.getMode() == RadioMode.RX) {
                            ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                            if (radioAudioService != null) {
                                // If the user tries to transmit, stop scanning so we don't
                                // move to a different frequency during or after the tx.
                                radioAudioService.setScanning(false, false);
                                setScanningUi(false);
                                radioAudioService.startPtt();
                            }
                            startPttUi(false);
                        } else if (radioAudioService != null && radioAudioService.getMode() == RadioMode.TX) {
                            ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                            if (radioAudioService != null) {
                                radioAudioService.endPtt();
                            }
                            endPttUi();
                        }
                    } else {
                        if (audioRecord == null) {
                            ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                            if (radioAudioService != null) {
                                // If the user tries to transmit, stop scanning so we don't
                                // move to a different frequency during or after the tx.
                                radioAudioService.setScanning(false, false);
                                setScanningUi(false);
                                radioAudioService.startPtt();
                            }
                            startPttUi(false);
                        }
                    }
                    touchHandled = true;
                    break;
                case MotionEvent.ACTION_UP:
                    if (!v.isClickable()) {
                        touchHandled = true;
                        break;
                    }
                    pttButtonDebounceHandler.removeCallbacksAndMessages(null);
                    if (!stickyPTT) {
                        pttButtonDebounceHandler.postDelayed(() -> {
                            if (radioAudioService != null) {
                                radioAudioService.endPtt();
                            }
                            endPttUi();
                        }, 250);
                    }
                    touchHandled = true;
                    break;
            }
            return touchHandled;
        });

        pttButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // This click handler is only for TalkBack users who also have stickyPTT enabled.
                // It's so they can use the typical quick double-tap to toggle PTT on and off. So
                // if stickyPTT isn't being used, don't handle a click on the PTT button (they need
                // to hold since it's not sticky).
                if (!stickyPTT) {
                    return;
                }

                if (null != radioAudioService && !radioAudioService.isTxAllowed()) {
                    showSimpleSnackbar(getString(R.string.can_t_tx_outside_ham_band));
                    return;
                }

                if (radioAudioService != null && radioAudioService.getMode() == RadioMode.RX) {
                    ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                    if (radioAudioService != null) {
                        radioAudioService.startPtt();
                    }
                    startPttUi(false);
                } else if (radioAudioService != null && radioAudioService.getMode() == RadioMode.TX) {
                    ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                    if (radioAudioService != null) {
                        radioAudioService.endPtt();
                    }
                    endPttUi();
                }
            }
        });

        EditText activeFrequencyField = findViewById(R.id.activeFrequency);
        activeFrequencyField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (radioAudioService != null) {
                    radioAudioService.tuneToFreq(activeFrequencyField.getText().toString(), squelch, false);
                    tuneToFreqUi(radioAudioService.makeSafeHamFreq(activeFrequencyField.getText().toString()), false); // Fixes any invalid freq user may have entered.
                }

                hideKeyboard();
                activeFrequencyField.clearFocus();
                return true;
            }
        });

        final View rootView = findViewById(android.R.id.content);
        final View frequencyView = findViewById(R.id.frequencyContainer);
        final EditText activeFrequencyEditText = findViewById(R.id.activeFrequency);
        final View rxAudioCircleView = findViewById(R.id.rxAudioCircle);

        // Track if keyboard is likely visible (and/or screen got short for some reason), so we can
        // make room for critical UI components that must be visible.
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                // When in chat, we need enough vertical space for the user to see their text
                // input box, and any prior chat message they may be replying to. Not necessary in
                // voice mode.
                if (activeScreenType != ScreenType.SCREEN_CHAT) {
                    return;
                }

                // If they're editing the frequency, don't change the layout (they need to see it).
                if (activeFrequencyEditText.hasFocus()) {
                    return;
                }

                // Get the height of the visible display area
                Rect r = new Rect();
                rootView.getWindowVisibleDisplayFrame(r);
                int screenHeight = rootView.getRootView().getHeight();
                int visibleHeight = r.height();

                // Check if the keyboard is likely visible
                int heightDiff = screenHeight - visibleHeight;

                if (heightDiff > screenHeight * 0.25) { // If more than 25% of the screen height is reduced
                    // Keyboard is visible, hide the top view
                    frequencyView.setVisibility(GONE);
                    rxAudioCircleView.setVisibility(GONE);
                } else {
                    // Keyboard is hidden, show the top view
                    frequencyView.setVisibility(VISIBLE);
                    rxAudioCircleView.setVisibility(VISIBLE);
                }
            }
        });
    }

    private void updateSMeter(int value) {
        if (value < 0 || value > 9) {
            Log.d("DEBUG", "Warning: Unexpected S-Meter value (" + value + ") in updateSMeter().");
            return;
        }

        final float S_METER_ON_ALPHA = 1.0f;
        final float S_METER_OFF_ALPHA = 0.2f;

        findViewById(R.id.sMeter1).setAlpha(value > 0 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);
        findViewById(R.id.sMeter2).setAlpha(value > 1 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);
        findViewById(R.id.sMeter3).setAlpha(value > 2 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);
        findViewById(R.id.sMeter4).setAlpha(value > 3 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);
        findViewById(R.id.sMeter5).setAlpha(value > 4 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);
        findViewById(R.id.sMeter6).setAlpha(value > 5 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);
        findViewById(R.id.sMeter7).setAlpha(value > 6 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);
        findViewById(R.id.sMeter8).setAlpha(value > 7 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);
        findViewById(R.id.sMeter9).setAlpha(value > 8 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);

        View sMeterView = findViewById(R.id.sMeter);
        sMeterView.setContentDescription("S meter " + value + " of 9");
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (null != imm) {
            imm.hideSoftInputFromWindow(findViewById(R.id.mainTopLevelLayout).getWindowToken(), 0);
        }
    }

    /**
     * Updates the UI to represent that we've tuned to the given frequency. Does not actually
     * interact with the radio (use RadioAudioService for that).
     */
    private void tuneToFreqUi(String frequencyStr, boolean wasForced) {
        final Context ctx = this;
        activeFrequencyStr = radioAudioService.validateFrequency(frequencyStr);
        activeMemoryId = -1;

        showMemoryName(getString(R.string.simplex));
        showFrequency(activeFrequencyStr);

        // Unhighlight all memory rows, since this is a simplex frequency.
        viewModel.highlightMemory(null);
        memoriesAdapter.notifyDataSetChanged();

        // Save most recent freq so we can restore it on app restart
        if (wasForced) { // wasForced means user didn't actually type in the frequency (we shouldn't save it)
            return;
        }
        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized(ctx) { // Avoid 2 threads checking if something is set / setting it at once.
                    AppSetting lastFreqSetting = viewModel.getAppDb().appSettingDao().getByName(AppSetting.SETTING_LAST_FREQ);
                    if (lastFreqSetting != null) {
                        lastFreqSetting.value = frequencyStr;
                        viewModel.getAppDb().appSettingDao().update(lastFreqSetting);
                    } else {
                        lastFreqSetting = new AppSetting(AppSetting.SETTING_LAST_FREQ, frequencyStr);
                        viewModel.getAppDb().appSettingDao().insertAll(lastFreqSetting);
                    }

                    // And clear out any saved memory ID, so we restore to a simplex freq on restart.
                    AppSetting lastMemoryIdSetting = viewModel.getAppDb().appSettingDao().getByName(AppSetting.SETTING_LAST_MEMORY_ID);
                    if (lastMemoryIdSetting != null) {
                        lastMemoryIdSetting.value = "-1";
                        viewModel.getAppDb().appSettingDao().update(lastMemoryIdSetting);
                    } else {
                        lastMemoryIdSetting = new AppSetting(AppSetting.SETTING_LAST_MEMORY_ID, "-1");
                        viewModel.getAppDb().appSettingDao().insertAll(lastMemoryIdSetting);
                    }
                }
            }
        });
    }

    /**
     * Updates the UI to represent that we've tuned to the given memory by ID. Does not actually
     * interact with the radio (use RadioAudioService for that).
     */
    private void tuneToMemoryUi(int memoryId) {
        List<ChannelMemory> channelMemories = viewModel.getChannelMemories().getValue();
        if (channelMemories == null) {
            return;
        }
        for (int i = 0; i < channelMemories.size(); i++) {
            if (channelMemories.get(i).memoryId == memoryId) {
                activeFrequencyStr = radioAudioService.validateFrequency(channelMemories.get(i).frequency);
                activeMemoryId = memoryId;

                showMemoryName(channelMemories.get(i).name);
                showFrequency(activeFrequencyStr);

                // Save most recent memory so we can restore it on app restart
                // Could be null if user is just listening to scan in another app, etc.
                threadPoolExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        AppSetting lastMemoryIdSetting = viewModel.getAppDb().appSettingDao().getByName(AppSetting.SETTING_LAST_MEMORY_ID);
                        if (lastMemoryIdSetting != null) {
                            lastMemoryIdSetting.value = "" + memoryId;
                            viewModel.getAppDb().appSettingDao().update(lastMemoryIdSetting);
                        } else {
                            lastMemoryIdSetting = new AppSetting(AppSetting.SETTING_LAST_MEMORY_ID, "" + memoryId);
                            viewModel.getAppDb().appSettingDao().insertAll(lastMemoryIdSetting);
                        }
                    }
                });
                return;
            }
        }
    }

    private void showMemoryName(String name) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView activeFrequencyField = findViewById(R.id.activeMemoryName);
                activeFrequencyField.setText(name);
            }
        });
    }

    private void showFrequency(String frequency) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                EditText activeFrequencyField = findViewById(R.id.activeFrequency);
                activeFrequencyField.setText(frequency);
                activeFrequencyStr = frequency;
            }
        });
    }

    public enum BandType {
        BAND_VHF, BAND_UHF, BAND_UNKNOWN
    }

    @SuppressWarnings("java:S3398")
    private void showBand(BandType bandType) {
        runOnUiThread(() -> {
            EditText bandField = findViewById(R.id.activeBand);
            if (bandField == null) return;
            switch (bandType) {
                case BAND_VHF:
                    bandField.setText(getString(R.string.vhf));
                    bandField.setVisibility(View.VISIBLE);
                    break;
                case BAND_UHF:
                    bandField.setText(getString(R.string.uhf));
                    bandField.setVisibility(View.VISIBLE);
                    break;
                default:
                    bandField.setVisibility(View.INVISIBLE);
                    break;
            }
        });
    }


    protected void startPttUi(boolean dataMode) {
        if (!dataMode) {
            startRecording();
        }
    }

    protected void endPttUi() {
        stopRecording();
    }

    protected void requestAudioPermissions() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.RECORD_AUDIO)) {

                new AlertDialog.Builder(this)
                        .setTitle("Permission needed")
                        .setMessage("This app needs the audio recording permission")
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                ActivityCompat.requestPermissions(MainActivity.this,
                                        new String[]{Manifest.permission.RECORD_AUDIO},
                                        REQUEST_AUDIO_PERMISSION_CODE);
                            }
                        })
                        .create()
                        .show();

            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_AUDIO_PERMISSION_CODE);
            }
        } else {
            // Permission has already been granted
        }
    }

    protected void requestFinePositionPermissions() {
        // Check that the user allows our app to get position, otherwise ask for the permission.
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)) {

                new AlertDialog.Builder(this)
                        .setTitle("Permission needed")
                        .setMessage("This app needs the fine location permission")
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                ActivityCompat.requestPermissions(MainActivity.this,
                                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                        REQUEST_FINE_LOCATION_PERMISSION_CODE);
                            }
                        })
                        .create()
                        .show();

            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_FINE_LOCATION_PERMISSION_CODE);
            }
        }
    }

    protected void requestNotificationPermissions() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.POST_NOTIFICATIONS)) {

                new AlertDialog.Builder(this)
                        .setTitle("Permission needed")
                        .setMessage("This app needs to be able to send notifications")
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                ActivityCompat.requestPermissions(MainActivity.this,
                                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                        REQUEST_NOTIFICATIONS_PERMISSION_CODE);
                            }
                        })
                        .create()
                        .show();

            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATIONS_PERMISSION_CODE);
            }
        } else {
            // Permission has already been granted
        }
    }

    protected void requestForegroundServiceLocationPermissions() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.FOREGROUND_SERVICE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.FOREGROUND_SERVICE_LOCATION)) {

                new AlertDialog.Builder(this)
                        .setTitle("Permission needed")
                        .setMessage("This app needs to know your location for APRS beaconing")
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                ActivityCompat.requestPermissions(MainActivity.this,
                                        new String[]{Manifest.permission.FOREGROUND_SERVICE_LOCATION},
                                        REQUEST_FOREGROUND_SERVICE_LOCATION_PERMISSION_CODE);
                            }
                        })
                        .create()
                        .show();

            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.FOREGROUND_SERVICE_LOCATION},
                        REQUEST_FOREGROUND_SERVICE_LOCATION_PERMISSION_CODE);
            }
        } else {
            // Permission has already been granted
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        switch (requestCode) {
            case REQUEST_AUDIO_PERMISSION_CODE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted.
                    initAudioRecorder();
                } else {
                    // Permission denied, things will just be broken.
                    Log.d("DEBUG", "Error: Need audio permission");
                }
                return;
            }
            case REQUEST_NOTIFICATIONS_PERMISSION_CODE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted.
                    tryToStartRadioAudioService();
                } else {
                    // Permission denied
                    Log.d("DEBUG", "Warning: Need notifications permission to be able to send APRS chat message notifications");
                }
                return;
            }
            case REQUEST_FINE_LOCATION_PERMISSION_CODE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted.
                    tryToStartRadioAudioService();
                } else {
                    // Permission denied
                    Log.d("DEBUG", "Warning: Need fine location permission to include location in APRS messages");
                }
                return;
            }
            case REQUEST_FOREGROUND_SERVICE_LOCATION_PERMISSION_CODE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted.
                    tryToStartRadioAudioService();
                } else {
                    // Permission denied
                    Log.d("DEBUG", "Warning: Need foreground service location permission for APRS beaconing");
                }
                return;
            }
        }
    }

    private void initAudioRecorder() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissions();
            return;
        }

        if (null != audioRecord) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                RadioAudioService.AUDIO_SAMPLE_RATE,
                channelConfig,
                audioFormat,
                minBufferSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.d("DEBUG", "Audio init error");
        }
    }

    private void startRecording() {
        if (audioRecord == null) {
            initAudioRecorder();
        }

        // After attempting to initialize, check if it's usable.
        if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.d("DEBUG", "AudioRecord not ready, cannot start recording.");
            // If it's not null, it's in a bad state. Release it so we can try again next time.
            if (audioRecord != null) {
                audioRecord.release();
                audioRecord = null;
            }
            return;
        }

        ImageButton pttButton = findViewById(R.id.pttButton);
        pttButton.setBackground(getDrawable(R.drawable.ptt_button_on));

        audioRecord.startRecording();
        isRecording = true;

        recordingThread = new Thread(new Runnable() {
            public void run() {
                processAudioStream();
            }
        }, "AudioRecorder Thread");

        recordingThread.start();
    }

    private void processAudioStream() {
        float audioChunkSampleTotal = 0.0f; // Accumulate across buffers
        int accumulatedSamples = 0; // Track count of samples
        int samplesPerAnimFrame = RadioAudioService.AUDIO_SAMPLE_RATE / RECORD_ANIM_FPS;
        float[] audioBuffer = new float[RadioAudioService.OPUS_FRAME_SIZE];
        while (isRecording) {
            int samples = audioRecord.read(audioBuffer, 0, RadioAudioService.OPUS_FRAME_SIZE, AudioRecord.READ_BLOCKING);
            if (samples == RadioAudioService.OPUS_FRAME_SIZE) {
                if (!radioAudioService.isRadioConnected()) {
                    throw new IllegalStateException("Radio not connected, cannot send audio.");
                }
                radioAudioService.sendAudioToESP32(audioBuffer, false);
                // Accumulate samples across buffers
                for (int i = 0; i < samples; i++) {
                    audioChunkSampleTotal += Math.abs(audioBuffer[i]) * 8.0f;
                    accumulatedSamples++;
                    // If we have enough samples, update visualization
                    if (accumulatedSamples >= samplesPerAnimFrame) {
                        updateRecordingVisualization(0, audioChunkSampleTotal / accumulatedSamples);
                        // Reset accumulators
                        audioChunkSampleTotal = 0.0f;
                        accumulatedSamples = 0;
                    }
                }
            }
        }
    }

    private void stopRecording() {
        if (audioRecord != null) {
            isRecording = false;
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
            recordingThread = null;
            updateRecordingVisualization(100, 0.0f);
        }
        ImageButton pttButton = findViewById(R.id.pttButton);
        pttButton.setBackground(getDrawable(R.drawable.ptt_button));
    }

    private void showUSBSnackbar() {
        CharSequence snackbarMsg = getString(R.string.radio_not_found);
        usbSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_INDEFINITE)
            .setBackgroundTint(Color.rgb(140, 20, 0)).setActionTextColor(Color.WHITE).setTextColor(Color.WHITE)
            .setAnchorView(findViewById(R.id.bottomNavigationView));

        // Make the text of the snackbar larger.
        TextView snackbarActionTextView = (TextView) usbSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = (TextView) usbSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        usbSnackbar.show();
    }

    private void showHandshakeSnackbar() {
        CharSequence snackbarMsg = getString(R.string.handshake_message);
        usbSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_INDEFINITE)
            .setBackgroundTint(getResources().getColor(R.color.primary))
            .setTextColor(getResources().getColor(R.color.medium_gray))
            .setAnchorView(findViewById(R.id.bottomNavigationView));

        // Make the text of the snackbar larger.
        TextView snackbarTextView = (TextView) usbSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        usbSnackbar.show();
    }

    private void showRadioModuleNotFoundSnackbar() {
        CharSequence snackbarMsg = getString(R.string.module_not_found_message);
        radioModuleNotFoundSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_INDEFINITE)
                .setBackgroundTint(Color.rgb(140, 20, 0)).setActionTextColor(Color.WHITE).setTextColor(Color.WHITE)
                .setAnchorView(findViewById(R.id.bottomNavigationView));

        // Make the text of the snackbar larger.
        TextView snackbarActionTextView = (TextView) radioModuleNotFoundSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = (TextView) radioModuleNotFoundSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        radioModuleNotFoundSnackbar.show();
    }

    /**
     * Alerts the user to missing or old firmware with the option to flash the latest.
     * @param firmwareVer The currently installed firmware version, or -1 if no firmware installed.
     */
    private void showVersionSnackbar(int firmwareVer) {
        final Context ctx = this;
        CharSequence snackbarMsg = firmwareVer == -1 ? getString(R.string.no_firmware_installed) : getString(R.string.new_firmware_available);
        versionSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_INDEFINITE)
                .setBackgroundTint(Color.rgb(140, 20, 0)).setActionTextColor(Color.WHITE).setTextColor(Color.WHITE)
                .setAction("Flash now", new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        startFirmwareActivity();
                    }
                })
                .setAnchorView(findViewById(R.id.bottomNavigationView));

        // Make the text of the snackbar larger.
        TextView snackbarActionTextView = (TextView) versionSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = (TextView) versionSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        versionSnackbar.show();
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            Log.d("DEBUG", "usbReceiver.onReceive()");

            String action = intent.getAction();
            synchronized (this) {
                if (ACTION_USB_PERMISSION.equals(action) || UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    if (radioAudioService != null) {
                        radioAudioService.reconnectViaUSB();
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    if (radioAudioService != null) {
                        radioAudioService.setScanning(false, true);
                    }
                    setScanningUi(false);
                }
            }
        }
    };

    public void scanClicked(View view) {
        setScanningUi((radioAudioService != null) && (radioAudioService.getMode()) != RadioMode.SCAN); // Toggle scanning on/off
        if (radioAudioService != null) {
            radioAudioService.setScanning(radioAudioService.getMode() != RadioMode.SCAN, true);
        }
    }

    public void singleBeaconButtonClicked(View view) {
        if (null != radioAudioService && !radioAudioService.isTxAllowed()) {
            showSimpleSnackbar(getString(R.string.can_t_tx_outside_ham_band));
            return;
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestFinePositionPermissions();
            return;
        }

        if (null != radioAudioService) {
            if (null == callsign || callsign.trim().length() == 0) {
                showCallsignSnackbar(getString(R.string.set_your_callsign_to_beacon_your_position));
                return;
            }

            radioAudioService.sendPositionBeacon();
        }
    }

    /**
     * Update the UI to reflect the scanning state. Does not actually interact with the radio,
     * that's handled by RadioAudioService.setScanning().
     */
    private void setScanningUi(boolean scanning) {
        AppCompatButton scanButton = findViewById(R.id.scanButton);
        if (!scanning) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    scanButton.setText(R.string.scan);
                }
            });

            // If squelch was off before we started scanning, turn it off again
            if (squelch == 0) {
                tuneToMemoryUi(activeMemoryId);
            }
        } else { // Start scanning
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    scanButton.setText(R.string.stop_scan);
                }
            });
        }
    }

    public void addMemoryClicked(View view) {
        Intent intent = new Intent("com.vagell.kv4pht.ADD_MEMORY_ACTION");
        intent.putExtra("requestCode", REQUEST_ADD_MEMORY);
        intent.putExtra("activeFrequencyStr", activeFrequencyStr);
        intent.putExtra("selectedMemoryGroup", selectedMemoryGroup);
        intent.putExtra("isVhfRadio", (radioAudioService != null && radioAudioService.getRadioType().equals(RadioAudioService.RadioModuleType.VHF)));

        startActivityForResult(intent, REQUEST_ADD_MEMORY);
    }

    public void selectImageClicked(View view) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(Intent.createChooser(intent, "Select Image"), REQUEST_SSTV_IMAGE);
    }

    public void encodeImageClicked(View view) {
        if (sstvImageUri == null) {
            doShowNotification("KV4P_HT_RADIO_AUDIO", 1001, "SSTV Error", "Select an image first", "");
            return;
        }
        try {
            InputStream is = getContentResolver().openInputStream(sstvImageUri);
            Bitmap original = BitmapFactory.decodeStream(is);
            if (is != null) is.close();
            if (original == null) {
                doShowNotification("KV4P_HT_RADIO_AUDIO", 1002, "SSTV Error", "Unable to load image", "");
                return;
            }
            Bitmap scaled = Bitmap.createScaledBitmap(original, 320, 256, true);
            ProgressBar progressBar = findViewById(R.id.sstvTransmitProgress);
            TextView statusView = findViewById(R.id.sstvTransmitStatus);
            AppCompatButton encodeBtn = findViewById(R.id.encodeButton);
            runOnUiThread(() -> {
                if (progressBar != null) { progressBar.setProgress(0); progressBar.setVisibility(VISIBLE); }
                if (statusView != null) { statusView.setVisibility(VISIBLE); statusView.setText("Encoding..."); }
                if (encodeBtn != null) encodeBtn.setEnabled(false);
            });
            threadPoolExecutor.execute(() -> {
                try {
                    sstvLastWavFile = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "sstv_last.wav");
                    if (NativeSSTV.isNativeAvailable()) {
                        byte[] wav = NativeSSTV.encodeRobot36ToWav(scaled);
                        if (wav != null) {
                            FileOutputStream fos = new FileOutputStream(sstvLastWavFile);
                            fos.write(wav);
                            fos.flush(); fos.close();
                        } else {
                            com.vagell.kv4pht.radio.sstv.Robot36Encoder encoder = new com.vagell.kv4pht.radio.sstv.Robot36Encoder(EXPORT_WAV_SAMPLE_RATE, com.vagell.kv4pht.radio.RadioAudioService.OPUS_FRAME_SIZE);
                            final int totalFrames = Math.max(1, 1);
                            encoder.encodeToWavFile(scaled, sstvLastWavFile, framesDone -> {
                                int pct = Math.min(100, (int)(framesDone * 100.0 / totalFrames));
                                runOnUiThread(() -> { if (progressBar != null) progressBar.setProgress(pct); if (statusView != null) statusView.setText("Encoding... " + pct + "%"); });
                            });
                        }
                    } else {
                        com.vagell.kv4pht.radio.sstv.Robot36Encoder encoder = new com.vagell.kv4pht.radio.sstv.Robot36Encoder(EXPORT_WAV_SAMPLE_RATE, com.vagell.kv4pht.radio.RadioAudioService.OPUS_FRAME_SIZE);
                        final int totalFrames = Math.max(1, 1);
                        encoder.encodeToWavFile(scaled, sstvLastWavFile, framesDone -> {
                            int pct = Math.min(100, (int)(framesDone * 100.0 / totalFrames));
                            runOnUiThread(() -> { if (progressBar != null) progressBar.setProgress(pct); if (statusView != null) statusView.setText("Encoding... " + pct + "%"); });
                        });
                    }
                    runOnUiThread(() -> {
                        if (progressBar != null) progressBar.setVisibility(GONE);
                        if (statusView != null) statusView.setVisibility(GONE);
                        if (encodeBtn != null) encodeBtn.setEnabled(true);
                        showSimpleSnackbar("Encode complete");
                    });
                } catch (Exception e) {
                    doShowNotification("KV4P_HT_RADIO_AUDIO", 1003, "SSTV Error", "Encode failed", "");
                }
            });
        } catch (Exception e) {
            doShowNotification("KV4P_HT_RADIO_AUDIO", 1004, "SSTV Error", "Image error", "");
        }
    }

    public void saveImageClicked(View view) {
        try {
            if (sstvLastWavFile != null && sstvLastWavFile.exists()) {
                String name = "SSTV_" + System.currentTimeMillis() + ".wav";
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "audio/wav");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    java.nio.file.Files.copy(sstvLastWavFile.toPath(), os);
                    if (os != null) os.close();
                    showSimpleSnackbar("Saved SSTV audio to Downloads");
                }
                return;
            }
        } catch (Exception e) {
            doShowNotification("KV4P_HT_RADIO_AUDIO", 1005, "SSTV Error", "Save failed", "");
            return;
        }
        transmitSSTVClicked(view);
    }


    public void transmitSSTVClicked(View view) {
        if (sstvImageUri == null) {
            showSimpleSnackbar("Select an image first");
            return;
        }
        try {
            InputStream is = getContentResolver().openInputStream(sstvImageUri);
            Bitmap original = BitmapFactory.decodeStream(is);
            if (is != null) is.close();
            if (original == null) {
                showSimpleSnackbar("Unable to load image");
                return;
            }
            Bitmap scaled = Bitmap.createScaledBitmap(original, 320, 256, true);
            boolean isConnected = radioAudioService != null && radioAudioService.isRadioConnected();
            boolean isAllowed = radioAudioService != null && radioAudioService.isTxAllowed();
            boolean canTx = isConnected && isAllowed;
            ProgressBar progressBar = findViewById(R.id.sstvTransmitProgress);
            TextView statusView = findViewById(R.id.sstvTransmitStatus);
            AppCompatButton txButton = findViewById(R.id.transmitButton);
            stopSSTVDecoding();
            runOnUiThread(() -> {
                if (progressBar != null) progressBar.setVisibility(VISIBLE);
                if (statusView != null) {
                    statusView.setVisibility(VISIBLE);
                    if (!isConnected) {
                        statusView.setText("KV4P-HT tidak terhubung • Encoding audio...");
                        showSimpleSnackbar("KV4P-HT tidak terhubung");
                    } else if (!isAllowed) {
                        statusView.setText("TX tidak diizinkan pada frekuensi aktif • Encoding audio...");
                        showSimpleSnackbar("TX tidak diizinkan pada frekuensi aktif");
                    } else {
                        statusView.setText("KV4P-HT terhubung • Encoding...");
                    }
                }
                if (txButton != null) txButton.setEnabled(false);
            });
            if (canTx) {
                if (radioAudioService != null) {
                    radioAudioService.setScanning(false, true);
                }
                setScanningUi(false);
                threadPoolExecutor.execute(() -> {
                    try {
                        radioAudioService.startPtt();
                        if (sstvTxSampleRate == EXPORT_WAV_SAMPLE_RATE) {
                            if (sstvLastWavFile == null || !sstvLastWavFile.exists()) {
                                sstvLastWavFile = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "sstv_last.wav");
                                byte[] wav = NativeSSTV.encodeRobot36ToWav(scaled);
                                if (wav == null) {
                                    com.vagell.kv4pht.radio.sstv.Robot36Encoder enc = new com.vagell.kv4pht.radio.sstv.Robot36Encoder(EXPORT_WAV_SAMPLE_RATE, com.vagell.kv4pht.radio.RadioAudioService.OPUS_FRAME_SIZE);
                                    enc.encodeToWavFile(scaled, sstvLastWavFile, null);
                                } else {
                                    FileOutputStream fos = new FileOutputStream(sstvLastWavFile);
                                    fos.write(wav); fos.flush(); fos.close();
                                }
                            }
                            WavPCM pcm = loadWavMono16(sstvLastWavFile);
                            if (pcm != null && pcm.samples != null && pcm.sampleRate > 0) {
                                int outRate = com.vagell.kv4pht.radio.RadioAudioService.AUDIO_SAMPLE_RATE;
                                int frameSize = com.vagell.kv4pht.radio.RadioAudioService.OPUS_FRAME_SIZE;
                                int totalOut = (int)(pcm.samples.length * (outRate / (double)pcm.sampleRate));
                                int totalFrames = Math.max(1, totalOut / frameSize);
                                int count = 0;
                                double pos = 0.0;
                                double step = (double)pcm.sampleRate / (double)outRate;
                                float[] out = new float[frameSize];
                                while (count < totalFrames) {
                                    for (int i = 0; i < frameSize; i++) {
                                        int idx = (int)pos;
                                        float s1 = pcm.samples[Math.min(idx, pcm.samples.length - 1)];
                                        float s2 = pcm.samples[Math.min(idx + 1, pcm.samples.length - 1)];
                                        float frac = (float)(pos - idx);
                                        out[i] = s1 + (s2 - s1) * frac;
                                        pos += step;
                                    }
                                    radioAudioService.sendAudioToESP32(out, true);
                                    count++;
                                    int pct = Math.min(100, (int)(count * 100.0 / totalFrames));
                                    runOnUiThread(() -> {
                                        if (progressBar != null) progressBar.setProgress(pct);
                                        if (statusView != null) statusView.setText("Transmitting... " + pct + "%");
                                    });
                                }
                            }
                        } else {
                            com.vagell.kv4pht.radio.sstv.Robot36Encoder encoder = new com.vagell.kv4pht.radio.sstv.Robot36Encoder();
                            final int totalFrames = Math.max(1, encoder.estimateTotalSamples(scaled) / com.vagell.kv4pht.radio.RadioAudioService.OPUS_FRAME_SIZE);
                            final int[] count = new int[]{0};
                            encoder.encode(scaled, frame -> {
                                try { radioAudioService.sendAudioToESP32(frame, true); } catch (Throwable ignored) {}
                                count[0]++;
                                int pct = Math.min(100, (int)(count[0] * 100.0 / totalFrames));
                                runOnUiThread(() -> {
                                    if (progressBar != null) progressBar.setProgress(pct);
                                    if (statusView != null) statusView.setText("Transmitting... " + pct + "%");
                                });
                            });
                        }
                    } catch (Throwable ignored) { }
                    finally {
                        try { radioAudioService.endDataTx(); } catch (Throwable ignored) {}
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try { if (radioAudioService != null && radioAudioService.getMode() == RadioMode.TX) radioAudioService.forceEndPtt(); } catch (Throwable ignored) {}
                        }, 500);
                        runOnUiThread(() -> {
                            if (statusView != null) statusView.setText("Done");
                            if (progressBar != null) progressBar.setVisibility(GONE);
                            if (statusView != null) statusView.setVisibility(GONE);
                            if (txButton != null) txButton.setEnabled(true);
                        });
                        startSSTVDecoding();
                    }
                });
            } else {
                threadPoolExecutor.execute(() -> {
                    try {
                        String name = "SSTV_" + System.currentTimeMillis() + ".wav";
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                        values.put(MediaStore.Downloads.MIME_TYPE, "audio/wav");
                        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                        if (uri != null) {
                            OutputStream os = getContentResolver().openOutputStream(uri);
                            if (os != null) {
                                com.vagell.kv4pht.radio.sstv.Robot36Encoder encoder = new com.vagell.kv4pht.radio.sstv.Robot36Encoder(EXPORT_WAV_SAMPLE_RATE, com.vagell.kv4pht.radio.RadioAudioService.OPUS_FRAME_SIZE);
                                File temp = File.createTempFile("sstv_tmp", ".wav", getCacheDir());
                                final int totalFrames = Math.max(1, 1);
                                encoder.encodeToWavFile(scaled, temp, framesDone -> {
                                    int pct = Math.min(100, (int)(framesDone * 100.0 / totalFrames));
                                    runOnUiThread(() -> {
                                        if (progressBar != null) progressBar.setProgress(pct);
                                        if (statusView != null) statusView.setText("Encoding audio... " + pct + "%");
                                    });
                                });
                                java.nio.file.Files.copy(temp.toPath(), os);
                                os.close();
                                runOnUiThread(() -> showSimpleSnackbar("Saved SSTV audio to Downloads"));
                            }
                        }
                        runOnUiThread(() -> {
                            if (progressBar != null) progressBar.setVisibility(GONE);
                            if (statusView != null) statusView.setVisibility(GONE);
                            if (txButton != null) txButton.setEnabled(true);
                        });
                    } catch (Exception ignored) { }
                });
            }
        } catch (Exception e) {
            showSimpleSnackbar("Image error");
        }
    }

    private void writeLE(java.io.ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }

    private void writeLEShort(java.io.ByteArrayOutputStream out, short v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }

    public void sstvSourceRadioClicked(View view) {
        sstvInputSource = SSTVInputSource.RADIO;
        TextView status = findViewById(R.id.sstvSourceStatus);
        if (status != null) status.setText(getString(R.string.sstv_source_prefix) + " " + getString(R.string.sstv_source_radio));
        if (activeScreenType == ScreenType.SCREEN_SSTV) {
            stopSSTVDecoding();
            startSSTVDecoding();
        }
    }

    public void sstvSourceMicClicked(View view) {
        sstvInputSource = SSTVInputSource.MICROPHONE;
        requestAudioPermissions();
        TextView status = findViewById(R.id.sstvSourceStatus);
        if (status != null) status.setText(getString(R.string.sstv_source_prefix) + " " + getString(R.string.sstv_source_mic));
        if (activeScreenType == ScreenType.SCREEN_SSTV) {
            stopSSTVDecoding();
            startSSTVDecoding();
        }
    }

    public void sstvTx48kClicked(View view) {
        sstvTxSampleRate = RadioAudioService.AUDIO_SAMPLE_RATE;
        showSimpleSnackbar("TX rate: 48 kHz");
    }

    public void sstvTx11025Clicked(View view) {
        sstvTxSampleRate = EXPORT_WAV_SAMPLE_RATE;
        showSimpleSnackbar("TX rate: 11025 Hz");
    }

    public void startDecodeClicked(View view) {
        ImageView preview = findViewById(R.id.sstvPreview);
        if (sstvInputSource == SSTVInputSource.RADIO) {
            if (radioAudioService == null) {
                showSimpleSnackbar(getString(R.string.radio_not_found));
                return;
            }
            int session = radioAudioService.getAudioTrackSessionId();
            if (session <= 0) {
                showSimpleSnackbar("Audio session unavailable");
                return;
            }
            try {
                if (sstvVisualizer != null) {
                    sstvVisualizer.release();
                }
                sstvVisualizer = new Visualizer(session);
                sstvVisualizer.setEnabled(false);
                int size = Visualizer.getCaptureSizeRange()[1];
                sstvVisualizer.setCaptureSize(size);
                com.vagell.kv4pht.radio.sstv.Robot36Decoder decoder = new com.vagell.kv4pht.radio.sstv.Robot36Decoder(
                        bmp -> runOnUiThread(() -> preview.setImageBitmap(bmp)),
                        bmp -> threadPoolExecutor.execute(() -> saveReceivedImage(bmp)));
                sstvVisualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                    @Override
                    public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                        float[] samples = new float[waveform.length];
                        for (int i = 0; i < waveform.length; i++) samples[i] = waveform[i] / 128f;
                        decoder.feed(samples, RadioAudioService.AUDIO_SAMPLE_RATE);
                    }
                    @Override
                    public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {}
                }, Visualizer.getMaxCaptureRate(), true, false);
                sstvVisualizer.setEnabled(true);
            } catch (Exception e) {
                showSimpleSnackbar("Visualizer error");
            }
        } else {
            try {
                if (sstvMicRecord != null) {
                    sstvMicRecord.stop();
                    sstvMicRecord.release();
                    sstvMicRecord = null;
                }
                sstvMicRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        RadioAudioService.AUDIO_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_FLOAT,
                        AudioRecord.getMinBufferSize(RadioAudioService.AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT));
                sstvMicRecord.startRecording();
                com.vagell.kv4pht.radio.sstv.Robot36Decoder decoder = new com.vagell.kv4pht.radio.sstv.Robot36Decoder(
                        bmp -> runOnUiThread(() -> preview.setImageBitmap(bmp)),
                        bmp -> threadPoolExecutor.execute(() -> saveReceivedImage(bmp)));
                threadPoolExecutor.execute(() -> {
                    float[] buf = new float[RadioAudioService.OPUS_FRAME_SIZE];
                    while (true) {
                        int n = sstvMicRecord.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
                        if (n <= 0) break;
                        decoder.feed(buf, RadioAudioService.AUDIO_SAMPLE_RATE);
                    }
                });
            } catch (Exception e) {
                showSimpleSnackbar("Mic error");
            }
        }
    }

    private void saveReceivedImage(Bitmap bmp) {
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US);
            String name = "sstv-Robot36-" + fmt.format(new java.util.Date()) + ".png";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os != null) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                    os.close();
                }
            }
        } catch (Exception ignored) { }
    }

    public void groupSelectorClicked(View view) {
        Context themedContext = new ContextThemeWrapper(this, R.style.Custom_PopupMenu);
        PopupMenu groupsMenu = new PopupMenu(themedContext, view);
        groupsMenu.inflate(R.menu.groups_menu);

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                List<String> memoryGroups = viewModel.getAppDb().channelMemoryDao().getGroups();
                for (int i = 0; i < memoryGroups.size(); i++) {
                    String groupName = memoryGroups.get(i);
                    if (groupName != null && groupName.trim().length() > 0) {
                        groupsMenu.getMenu().add(memoryGroups.get(i));
                    }
                }

                groupsMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        selectMemoryGroup(item.getTitle().toString());
                        return true;
                    }
                });

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        groupsMenu.show();
                    }
                });
            }
        });
    }

    private void selectMemoryGroup(String groupName) {
        this.selectedMemoryGroup = groupName.equals(getString(R.string.all_memories)) ? null : groupName;
        viewModel.loadDataAsync(() -> {});
        // Add drop-down arrow to end of selected group to suggest it's tappable
        TextView groupSelector = findViewById(R.id.groupSelector);
        groupSelector.setText(groupName + " ▼");

        // Save most recent group selection so we can restore it on app restart
        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting lastGroupSetting = viewModel.getAppDb().appSettingDao().getByName(AppSetting.SETTING_LAST_GROUP);
                if (lastGroupSetting != null) {
                    lastGroupSetting.value = groupName;
                    viewModel.getAppDb().appSettingDao().update(lastGroupSetting);
                } else {
                    lastGroupSetting = new AppSetting(AppSetting.SETTING_LAST_GROUP, groupName);
                    viewModel.getAppDb().appSettingDao().insertAll(lastGroupSetting);
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQUEST_ADD_MEMORY:
                if (resultCode == Activity.RESULT_OK) {
                    viewModel.loadDataAsync(() -> runOnUiThread(() -> memoriesAdapter.notifyDataSetChanged()));
                }
                break;
            case REQUEST_SSTV_IMAGE:
                if (resultCode == Activity.RESULT_OK && data != null) {
                    sstvImageUri = data.getData();
                    ImageButton btn = findViewById(R.id.selectImageButton);
                    if (btn != null && sstvImageUri != null) {
                        btn.setImageURI(sstvImageUri);
                    }
                }
                break;
            case REQUEST_EDIT_MEMORY:
                if (resultCode == Activity.RESULT_OK) {
                    // Add an observer to the model so we know when it's done reloading
                    // the edited memory, so we can tune to it.
                    final int editedMemoryId = data.getExtras().getInt("memoryId");
                    viewModel.loadDataAsync(() -> runOnUiThread(() -> {
                        memoriesAdapter.notifyDataSetChanged();
                        // Tune to the edited memory to force any changes to be applied (e.g. new tone
                        // or frequency).
                        List<ChannelMemory> channelMemories = viewModel.getChannelMemories().getValue();
                        for (int i = 0; i < channelMemories.size(); i++) {
                            if (channelMemories.get(i).memoryId == editedMemoryId) {
                                viewModel.highlightMemory(channelMemories.get(i));

                                if (radioAudioService != null) {
                                    radioAudioService.tuneToMemory(channelMemories.get(i), squelch, false);
                                }

                                tuneToMemoryUi(channelMemories.get(i).memoryId);
                            }
                        }
                    }));
                }
                break;
            case REQUEST_SETTINGS:
                // Don't need to do anything here, since settings are applied in onResume() anyway.
                break;
            case REQUEST_FIRMWARE:
                if (resultCode == Activity.RESULT_OK) {
                    showSimpleSnackbar(getString(R.string.successfully_updated_firmware));

                    // Try to reconnect now that the kv4p HT firmware should be present
                    if (null != radioAudioService) {
                        radioAudioService.reconnectViaUSB();
                    }
                }
                break;
            default:
                Log.d("DEBUG", "Warning: Returned to MainActivity from unexpected request code: " + requestCode);
        }
    }

    private void showSimpleSnackbar(String msg) {
        Snackbar simpleSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), msg, LENGTH_LONG)
                .setBackgroundTint(getResources().getColor(R.color.primary))
                .setTextColor(getResources().getColor(R.color.medium_gray));

        // Make the text of the snackbar larger.
        TextView snackbarTextView = (TextView) simpleSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        simpleSnackbar.show();
    }

    private void startSSTVDecoding() {
        ImageView preview = findViewById(R.id.sstvPreview);
        ImageView scopeView = findViewById(R.id.sstvScope);
        sstvScopeBitmap = Bitmap.createBitmap(400, 120, Bitmap.Config.ARGB_8888);
        sstvScopeCanvas = new Canvas(sstvScopeBitmap);
        sstvScopePaint = new Paint();
        if (scopeView != null) scopeView.setImageBitmap(sstvScopeBitmap);
        NativeSSTV.startRobot36Decoder(RadioAudioService.AUDIO_SAMPLE_RATE);
        if (sstvInputSource == SSTVInputSource.RADIO) {
            if (radioAudioService == null) {
                showSimpleSnackbar(getString(R.string.radio_not_found));
                return;
            }
            int session = radioAudioService.getAudioTrackSessionId();
            if (session <= 0) {
                showSimpleSnackbar("Audio session unavailable");
                return;
            }
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestAudioPermissions();
                    return;
                }
                if (sstvVisualizer != null) {
                    sstvVisualizer.setEnabled(false);
                    sstvVisualizer.release();
                }
                try {
                    sstvVisualizer = new Visualizer(session);
                } catch (Exception e) {
                    sstvVisualizer = new Visualizer(0);
                }
                sstvVisualizer.setEnabled(false);
                int size = Visualizer.getCaptureSizeRange()[1];
                sstvVisualizer.setCaptureSize(size);
                sstvVisualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                    @Override
                    public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                        float[] samples = new float[waveform.length];
                        for (int i = 0; i < waveform.length; i++) samples[i] = (waveform[i] - 128f) / 128f;
                        NativeSSTV.feedRobot36Samples(samples, RadioAudioService.AUDIO_SAMPLE_RATE);
                        updateSSTVScope(samples, RadioAudioService.AUDIO_SAMPLE_RATE);
                        com.vagell.kv4pht.radio.sstv.Robot36Progress p = NativeSSTV.getRobot36Progress();
                        if (p != null && p.bitmap != null) {
                            runOnUiThread(() -> {
                                preview.setImageBitmap(p.bitmap);
                                TextView status = findViewById(R.id.sstvSourceStatus);
                                if (status != null) status.setText("Robot36 • " + p.state + " • " + p.lineIndex + "/240");
                                if (p.completed) threadPoolExecutor.execute(() -> saveReceivedImage(p.bitmap));
                            });
                        }
                    }
                    @Override
                    public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {}
                }, Visualizer.getMaxCaptureRate(), true, false);
                sstvVisualizer.setEnabled(true);
            } catch (Exception e) {
                showSimpleSnackbar("Visualizer unavailable");
            }
        } else {
            try {
                if (sstvMicRecord != null) {
                    sstvMicRecord.stop();
                    sstvMicRecord.release();
                }
                sstvMicRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        RadioAudioService.AUDIO_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_FLOAT,
                        AudioRecord.getMinBufferSize(RadioAudioService.AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT));
                sstvMicRecord.startRecording();
                threadPoolExecutor.execute(() -> {
                    float[] buf = new float[RadioAudioService.OPUS_FRAME_SIZE];
                    while (sstvMicRecord != null) {
                        int n = sstvMicRecord.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
                        if (n <= 0) break;
                        NativeSSTV.feedRobot36Samples(buf, RadioAudioService.AUDIO_SAMPLE_RATE);
                        updateSSTVScope(buf, RadioAudioService.AUDIO_SAMPLE_RATE);
                        com.vagell.kv4pht.radio.sstv.Robot36Progress p = NativeSSTV.getRobot36Progress();
                        if (p != null && p.bitmap != null) {
                            runOnUiThread(() -> {
                                preview.setImageBitmap(p.bitmap);
                                TextView status = findViewById(R.id.sstvSourceStatus);
                                if (status != null) status.setText("Robot36 • " + p.state + " • " + p.lineIndex + "/240");
                                if (p.completed) threadPoolExecutor.execute(() -> saveReceivedImage(p.bitmap));
                            });
                        }
                    }
                });
            } catch (Exception e) {
                showSimpleSnackbar("Mic error");
            }
        }
    }

    private void updateSSTVScope(float[] samples, int sr) {
        if (sstvScopeCanvas == null || sstvScopePaint == null) return;
        sstvScopeCanvas.drawColor(0xFF202020);
        float e1200 = goertzel(samples, sr, 1200f);
        float e1500 = goertzel(samples, sr, 1500f);
        float e2300 = goertzel(samples, sr, 2300f);
        int w = sstvScopeBitmap.getWidth();
        int h = sstvScopeBitmap.getHeight();
        sstvScopePaint.setColor(0xFF00FF00);
        sstvScopeCanvas.drawRect(w*0.1f, h - Math.min(h, e1200 * h), w*0.25f, h, sstvScopePaint);
        sstvScopePaint.setColor(0xFF0088FF);
        sstvScopeCanvas.drawRect(w*0.4f, h - Math.min(h, e1500 * h), w*0.55f, h, sstvScopePaint);
        sstvScopePaint.setColor(0xFFFF8800);
        sstvScopeCanvas.drawRect(w*0.7f, h - Math.min(h, e2300 * h), w*0.85f, h, sstvScopePaint);
        ImageView scopeView = findViewById(R.id.sstvScope);
        runOnUiThread(() -> { if (scopeView != null) scopeView.setImageBitmap(sstvScopeBitmap); });
    }

    private float goertzel(float[] x, int fs, float f) {
        float s=0f,s1=0f,s2=0f;
        float w = (float)(2.0 * Math.PI * f / fs);
        float c = (float)Math.cos(w);
        float k = 2f * c;
        for (int i = 0; i < x.length; i++) {
            float v = x[i];
            s = k * s1 - s2 + v;
            s2 = s1;
            s1 = s;
        }
        float re = s1 - c * s2;
        float im = (float)Math.sin(w) * s2;
        float m = (float)Math.sqrt(re * re + im * im);
        return Math.min(1f, m);
    }

    private void stopSSTVDecoding() {
        try {
            if (sstvVisualizer != null) { sstvVisualizer.setEnabled(false); sstvVisualizer.release(); sstvVisualizer = null; }
        } catch (Exception ignored) {}
        try {
            if (sstvMicRecord != null) { sstvMicRecord.stop(); sstvMicRecord.release(); sstvMicRecord = null; }
        } catch (Exception ignored) {}
        NativeSSTV.stopRobot36Decoder();
    }

    private void startFirmwareActivity() {
        // Stop any scanning or transmitting
        if (radioAudioService != null) {
            radioAudioService.setScanning(false);
            radioAudioService.endPtt();
        }
        endPttUi();
        setScanningUi(false);

        // Tell the radioAudioService to hold on while we flash.
        radioAudioService.setMode(RadioMode.FLASHING);

        // Actually start the firmware activity
        Intent intent = new Intent("com.vagell.kv4pht.FIRMWARE_ACTION");
        intent.putExtra("requestCode", REQUEST_FIRMWARE);
        startActivityForResult(intent, REQUEST_FIRMWARE);
    }

    private void startFindRepeatersActivity() {
        // Stop any scanning or transmitting
        if (radioAudioService != null) {
            radioAudioService.setScanning(false);
            radioAudioService.endPtt();
        }
        endPttUi();
        setScanningUi(false);

        // Actually start the find repeaters activity
        Intent intent = new Intent("com.vagell.kv4pht.FIND_REPEATERS");
        startActivityForResult(intent, REQUEST_FIND_REPEATERS);
    }

    public void startSettingsActivity() {
        if (radioAudioService != null) {
            radioAudioService.setScanning(false); // Stop scanning when settings brought up, so we don't get in a bad state after.
            radioAudioService.endPtt(); // Be safe, just in case we are somehow transmitting when settings is tapped.
        }
        endPttUi();
        setScanningUi(false);

        Intent intent = new Intent("com.vagell.kv4pht.SETTINGS_ACTION");
        intent.putExtra("requestCode", REQUEST_SETTINGS);
        if (radioAudioService != null && radioAudioService.isRadioConnected()) {
            intent.putExtra("hasHighLowPowerSwitch", radioAudioService.isHasHighLowPowerSwitch());
            intent.putExtra("firmwareVersion", firmwareVersion);
        }
        startActivityForResult(intent, REQUEST_SETTINGS);
    }

    public void moreClicked(View view) {
        Context themedContext = new ContextThemeWrapper(this, R.style.Custom_PopupMenu);
        PopupMenu moreMenu = new PopupMenu(themedContext, view);
        moreMenu.inflate(R.menu.more_menu);
        moreMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.import_from_repeaterbook) {
                    startFindRepeatersActivity();
                } else if (item.getItemId() == R.id.flash_firmware) {
                    startFirmwareActivity();
                } else if (item.getItemId() == R.id.settings) {
                    startSettingsActivity();
                } else if (item.getItemId() == R.id.open_robot36) {
                    try {
                        Intent intent = new Intent(MainActivity.this, Robot36Activity.class);
                        startActivity(intent);
                    } catch (Exception e) {
                        try {
                            Intent ext = new Intent();
                            ext.setClassName("xdsopl.robot36", "xdsopl.robot36.MainActivity");
                            startActivity(ext);
                        } catch (Exception ex) {
                            showSimpleSnackbar("Robot36 tidak tersedia");
                        }
                    }
                }
                return true;
            }
        });
        moreMenu.show();
    }

    /**
     * Shows a notification to the user.
     *
     * @param notificationChannelId The ID of the notification channel.
     * @param notificationTypeId    The ID for the notification type.
     * @param title                 The title of the notification.
     * @param message               The message content of the notification.
     * @param tapIntentName         The intent action name to handle taps on the notification.
     */
    public void doShowNotification(String notificationChannelId, int notificationTypeId, String title, String message, String tapIntentName) {
        if (notificationChannelId == null || title == null || message == null) {
            Log.d("DEBUG", "Unexpected null in showNotification.");
            return;
        }
        // Has the user disallowed notifications?
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        // If they tap the notification when doing something else, come back to this app
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(tapIntentName);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // Notify the user they got a message.
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, notificationChannelId)
            .setSmallIcon(R.drawable.ic_chat_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) // Dismiss on tap
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.notify(notificationTypeId, builder.build());
    }
}

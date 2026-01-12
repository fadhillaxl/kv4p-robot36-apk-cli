## Outcome
- Upload an image, preview it, resize to `320x256`, encode in Robot36 (YC), and transmit over PTT using the existing 48 kHz audio path.

## UI Wiring
- `MainActivity.showScreen(...)` (app/src/main/java/com/vagell/kv4pht/ui/MainActivity.java:757):
  - For `SCREEN_SSTV`: hide `textModeContainer`, show `sstvModeContainer`, stop scanning via `radioAudioService.setScanning(false, true)` and `setScanningUi(false)`.
- Add request code and state:
  - Near other request codes (MainActivity.java:143): `public static final int REQUEST_SSTV_IMAGE = 5;`
  - Add `private android.net.Uri sstvImageUri = null;`
- Implement `selectImageClicked(View)`:
  - Launch `Intent.ACTION_GET_CONTENT` with `image/*` and handle with `startActivityForResult(...)` using `REQUEST_SSTV_IMAGE`.
- In `onActivityResult(...)` (MainActivity.java:1891):
  - On `REQUEST_SSTV_IMAGE` and `RESULT_OK`: `sstvImageUri = data.getData();` and preview via `((ImageButton) findViewById(R.id.selectImageButton)).setImageURI(sstvImageUri);`

## Transmit Button
- Implement `transmitSSTVClicked(View view)`:
  - Guard: `radioAudioService != null`, `radioAudioService.isTxAllowed()`, `sstvImageUri != null`. Show snackbar on failure.
  - Stop scanning (`radioAudioService.setScanning(false, true); setScanningUi(false);`).
  - Load bitmap from `sstvImageUri`, resize to `320x256` using `BitmapFactory` and `Bitmap.createScaledBitmap(...)`.
  - On a background thread: `radioAudioService.startPtt();` stream encoder output in `OPUS_FRAME_SIZE` chunks via `radioAudioService.sendAudioToESP32(samples, true);` then tail silence and `radioAudioService.endPtt();`.

## Encoder Implementation
- New file: `app/src/main/java/com/vagell/kv4pht/radio/sstv/Robot36Encoder.java`.
- API: `void encode(android.graphics.Bitmap bmp, java.util.function.Consumer<float[]> out)`; emits 48 kHz mono PCM batches sized `RadioAudioService.OPUS_FRAME_SIZE`.
- Tone generation: continuous-phase oscillator, per-sample `phase += 2π * f / 48000; sample = (float) Math.sin(phase);`.
- VIS header (Robot36 color code `0x28`): leader 1900 Hz 300 ms, break 1200 Hz 10 ms, leader 1900 Hz 300 ms, start 1200 Hz 30 ms, 8 bits LSB-first with 1→1100 Hz 30 ms, 0→1300 Hz 30 ms, stop 1200 Hz 30 ms. Reference: SSTV handbook VIS table (https://www.sstv-handbook.com/download/sstv_03.pdf) and implementation pattern (https://github.com/brainwagon/sstv-encoders/blob/master/robot36.c).
- Per scanline (240 total, ~36 s image):
  - Sync 1200 Hz 9 ms, porch 1500 Hz 3 ms.
  - Luma Y for 88 ms mapping 320 pixels → 1500–2300 Hz.
  - Separator 4 ms: even lines 1500 Hz, odd lines 2300 Hz. Reference: Barber paper (http://www.barberdsp.com/downloads/Dayton%20Paper.pdf).
  - Porch 1500 Hz 3 ms.
  - Chrominance 44 ms: odd lines Cb (B−Y), even lines Cr (R−Y); horizontally downsample to 160 samples (average pairs) to match duration. General mode characteristics: DigiGrup overview (https://www.digigrup.org/ccdd/sstv.htm).
- Frequency mapping: `freq = 1500.0f + (value/255.0f) * 800.0f` (data band 1500–2300 Hz). Consistent with Robot36 decoders (https://github.com/xdsopl/robot36/blob/master/decode.c).
- Tail: ~700 ms silence to ensure clean end (same reason as APRS tail in this app).

## Integration Notes
- Keep sample rate at 48 kHz to fit `RadioAudioService` Opus framing (`OPUS_FRAME_SIZE=1920`).
- Use `dataMode=true` when sending to bypass mic gain: `sendAudioToESP32(samples, true)`.
- Ensure no comments in code; follow existing patterns (threadPoolExecutor for background work, snackbar for feedback).

## Verification
- Decode the transmitted audio with a Robot36 decoder to confirm VIS, timing, and image color alignment.
- Confirm UI: image selection preview works; SSTV tab toggling hides voice/chat controls; PTT engages only during transmit.

## Deliverables
- Updated `MainActivity` with image picker, screen wiring, transmit handler.
- New `Robot36Encoder` class producing 48 kHz PCM for Robot36.
- Explanation and safety checks for band TX restrictions remain intact (`isTxAllowed`).
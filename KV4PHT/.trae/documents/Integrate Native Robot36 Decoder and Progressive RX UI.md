## Current State
- Native encoder present at `app/src/main/cpp/robot36.c` (Robot36 WAV at 11025 Hz).
- JNI stubs exist; decode path returns `null` in `app/src/main/cpp/native-lib.cpp:31-33`.
- Java decoder `com.vagell.kv4pht.radio.sstv.Robot36Decoder` drives preview/scopes but lacks robust VIS/line sync and chroma alternation.
- RX sources feed at `48000 Hz` via `Visualizer` and `AudioRecord`; no resampling to `11025 Hz`.

## Objectives
1. Native Robot36 decode with proper VIS lock and per-line sync.
2. Resample arbitrary input rates to `11025 Hz` for decoder stability.
3. Progressive UI updates (per-line), status strip, and auto-save on frame completion.
4. Clean Java path after native integration; keep TX encoder as-is.

## Native Integration
1. Vendor decoder sources:
   - Add `app/src/main/cpp/robot36_decode/` with upstream `xdsopl/robot36` decoder files (minimal set: demodulator/decoder + YCbCr → RGB, VIS, line synchronizer, progressive callbacks).
2. CMake:
   - Update `app/src/main/cpp/CMakeLists.txt:4-8` to include decoder sources:
     - Append `robot36_decode/*.c` to `add_library(native-lib SHARED ...)` or use `file(GLOB ...)` to collect them.
   - Link stays with `log`, `jnigraphics`, `m` (already configured at `app/src/main/cpp/CMakeLists.txt:18-22`).

## JNI API
Implement lifecycle and feed in `app/src/main/cpp/native-lib.cpp`:
- `startRobot36Decoder(int sampleRate)`
  - Create decoder context; set input sample rate and internal resampler target `11025`.
- `feedRobot36Samples(float[] samples, int sampleRate)`
  - Resample to `11025 Hz`; push PCM into decoder; advance state machine; cache progressive bitmap buffer (ARGB_8888 320×256); track `lineIndex` and state (Leader/Vis/Sync/Image).
- `getRobot36Progress()`
  - Return a small Java object with: `int lineIndex` and `Bitmap partial`; also include `String mode="Robot36"` and `String state` for status strip.
  - Note: Create `com.vagell.kv4pht.radio.sstv.Robot36Progress` (fields: `int lineIndex`, `Bitmap bitmap`, `String state`, `boolean completed`).
- `stopRobot36Decoder()`
  - Free native decoder, resampler buffers.

Java declarations in `app/src/main/java/com/vagell/kv4pht/radio/sstv/NativeSSTV.java:10-13`:
- Add `static native void startRobot36Decoder(int sampleRate);`
- Add `static native void feedRobot36Samples(float[] samples, int sampleRate);`
- Add `static native Robot36Progress getRobot36Progress();`
- Add `static native void stopRobot36Decoder();`

## Resampling
- Implement lightweight linear interpolator in native code:
  - Maintain phase accumulator; convert incoming `float PCM` (`-1..1`) to `int16` at `11025 Hz`.
  - Avoid external dependencies; ensure continuity across feeds.
- Accept both `Visualizer` waveform and `AudioRecord` buffers; use provided `sampleRate` to drive ratio.

## Capture Wiring
Update `app/src/main/java/com/vagell/kv4pht/ui/MainActivity.java`:
- Replace Java decoder creation at `startSSTVDecoding()`:
  - `2328-2331`: remove `Robot36Decoder` init; call `NativeSSTV.startRobot36Decoder(RadioAudioService.AUDIO_SAMPLE_RATE)`.
- Visualizer path `2360-2365` and mic path `2385-2392`:
  - After converting to `float[] samples`, call `NativeSSTV.feedRobot36Samples(samples, RadioAudioService.AUDIO_SAMPLE_RATE)`.
  - Immediately call `NativeSSTV.getRobot36Progress()`; update `sstvPreview` with `progress.bitmap` and update `sstvSourceStatus` with `progress.state` and `progress.lineIndex` (e.g., `Robot36 • Image • 120/240`).
- On screen exit or source switch:
  - In `stopSSTVDecoding() 2435-2443`, call `NativeSSTV.stopRobot36Decoder()`.

## RX UI
- Progressive render: use `progress.bitmap` directly for full-width preview (`ImageView @+id/sstvPreview`).
- Status strip: reuse `@+id/sstvSourceStatus` (`activity_main.xml:529-537`), set text to `Robot36 • <state> • <line>/240`.
- Optional: hide scope by default; keep a toggle if desired. Minimal change: leave scope visible for now; later add a toggle in menu.

## Auto-Save
- On `progress.completed == true` (lineIndex == 240):
  - Save PNG: use existing `saveReceivedImage(...)` path but switch to PNG and name `sstv-Robot36-YYYYMMDD-HHmmss.png`.
  - Reset status to `Idle` and restart decoder for next frame if remaining audio is flowing.

## Cleanup
- Remove Java `Robot36Decoder` uses:
  - `MainActivity.java:2328-2331`, `2363-2364`, `2390-2391`.
- Keep TX encoder:
  - Native encoder `robot36.c` and `NativeSSTV.encodeRobot36ToWav(...)` already wired (`MainActivity.java:1910-1911`).
- Optionally delete `com.vagell.kv4pht.radio.sstv.Robot36Decoder.java` after native path is stable.

## Validation
- Live RX from radio and microphone:
  - Confirm VIS lock transitions: Leader → Break → VIS → Lines; verify status updates.
  - Ensure chroma alternation and line alignment produce correct color.
  - Verify Visualizer feed at `48 kHz` is resampled correctly; no drift across long captures.
- Completion:
  - Confirm auto-save triggers and file appears in `Pictures/` with expected name.
- Error handling:
  - Add safe-guards in JNI for null buffers, invalid rates; emit notifications via existing snackbar helpers.

## Risks & Mitigations
- Decoder source licensing/porting: use upstream compatible license; attribute in notices.
- UI jank from frequent `Bitmap` updates: batch updates (e.g., every N lines or ~100 ms) to balance smoothness and performance.
- Visualizer capture size variability: normalize by resampler and maintain internal ring buffer before decode.

## Code References
- Native encoder: `app/src/main/cpp/robot36.c:84-102`.
- JNI stubs: `app/src/main/cpp/native-lib.cpp:13-28`, `31-33`.
- CMake: `app/src/main/cpp/CMakeLists.txt:4-8`, `18-22`.
- Java native bridge: `app/src/main/java/com/vagell/kv4pht/radio/sstv/NativeSSTV.java:10-13`.
- RX wiring and UI: `app/src/main/java/com/vagell/kv4pht/ui/MainActivity.java:2321-2397`, `2400-2433`, `2435-2443`.
- Layout: `app/src/main/res/layout/activity_main.xml:481-563`.

If you approve, I will implement the native decoder, JNI API, resampler, wire the UI, and remove the fragile Java decoder path as outlined.
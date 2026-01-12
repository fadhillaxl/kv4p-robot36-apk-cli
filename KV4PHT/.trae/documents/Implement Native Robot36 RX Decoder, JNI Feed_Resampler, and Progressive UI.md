## Decoder Sources
- Add native Robot36 decoder from xdsopl/robot36 under `app/src/main/cpp/robot36_decode/` (minimal set: vis detection, sync, Y/chroma demodulation).
- Keep existing encoder native; expand CMake to compile decoder files.

## CMake and Linking
- Update `app/src/main/cpp/CMakeLists.txt`:
  - Include decoder sources in `add_library(native-lib SHARED ...)`.
  - Ensure `jnigraphics`, `log`, and `m` are linked.

## JNI Interface
- Extend `NativeSSTV.java` with decode methods:
  - `static native boolean startRobot36Decoder(int sampleRate);`
  - `static native void feedRobot36Samples(float[] samples, int sampleRate);`
  - `static native int getRobot36LineIndex();` (returns current decoded line `0..240`)
  - `static native android.graphics.Bitmap getRobot36Bitmap();` (returns current 320×256 ARGB)
  - `static native void stopRobot36Decoder();`
- Implement in `native-lib.cpp`:
  - Maintain decoder context (VIS state, line index, working image buffer).
  - Resample incoming PCM from 48 kHz to 11025 Hz using a simple linear resampler with pre‑LPF (one‑pole IIR) to avoid aliasing (baseband 2.3 kHz → acceptable).
  - On VIS lock and per‑line sync, fill Y and chroma into a line buffer, convert to RGB, write into ARGB bitmap.
  - Provide progressive bitmap back via `getRobot36Bitmap`.

## Capture Pipelines
- Radio RX: Visualizer on service audio session → `onWaveFormDataCapture` → convert bytes to floats → call `feedRobot36Samples(samples, 48000)`.
- Mic RX: `AudioRecord` at 48 kHz mono (PCM float) → worker thread reads buffers → call `feedRobot36Samples(buf, 48000)`.
- Auto start decoder on entering SSTV tab: call `startRobot36Decoder(48000)`; stop when leaving: `stopRobot36Decoder()`.

## Progressive UI (RX Display)
- Layout (`activity_main.xml`):
  - Keep `@id/sstvPreview` as full‑width image area.
  - Add status strip: `@id/sstvVisStatus` (e.g., "Robot36"), `@id/sstvDecodeState` (Leader/Vis/Sync/Image), `@id/sstvLineCounter` (e.g., `Line 123/240`).
  - Add `Start` and `Stop` buttons: `startSstvRxClicked`, `stopSstvRxClicked`.
- `MainActivity`:
  - In a periodic UI task (e.g., `Handler` or executor), poll `getRobot36LineIndex()` and `getRobot36Bitmap()` while decoding → update preview and line counter.
  - Hide scope by default; optional toggle to show.

## Auto‑Save
- When line index reaches 240, save bitmap via `MediaStore.Images.Media` as `sstv-Robot36-YYYYMMDD-HHmmss.png` in Pictures.
- Reset decoder to Idle after save; auto‑restart for next frame or wait for Start.

## Error Handling
- If Visualizer session not available or mic permission missing, post notification (existing `doShowNotification`).
- If JNI returns null or decoder context not initialized, show notification and fallback (optional) to Java path.

## Cleanup
- Remove Java `Robot36Decoder` and scope energy bars once native decoding is verified.
- Keep TX at 48 kHz and export WAV at 11025 Hz as implemented.

## Validation
- Test with known Robot36 transmissions:
  - Confirm VIS lock and mode display.
  - Observe progressive per‑line rendering with accurate color.
  - Auto‑save triggers at completion.
  - Decode works from both radio (Visualizer) and microphone sources.

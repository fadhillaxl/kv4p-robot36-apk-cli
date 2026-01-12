## Decoder Integration
- Add native Robot36 decoder sources (xdsopl/robot36) under `app/src/main/cpp/robot36_decode/`.
- Update CMake to compile decoder and link with `log`, `jnigraphics`, `m`.

## JNI API
- Implement decoder lifecycle:
  - `startRobot36Decoder(int sampleRate)` to initialize.
  - `feedRobot36Samples(float[] samples, int sampleRate)` to push PCM; resample to 11025 Hz internally when required.
  - `getRobot36Bitmap()` returns progressive 320×256 ARGB bitmap or latest partial frame.
  - `stopRobot36Decoder()` to clean up.

## Capture Pipelines
- Radio RX: Use Visualizer on service audio session; push waveform buffers to `feedRobot36Samples` on a worker thread.
- Mic RX: Use `AudioRecord` at 48 kHz mono; feed to decoder and resample in native.
- Auto-start decode when entering SSTV, stop on leaving.

## UI (RX Display Like Robot36)
- Replace SSTV RX area with full-width progressive image render.
- Status strip: VIS mode (Robot36), state (Leader/Vis/Sync/Image), line counter (e.g., `Line 123/240`).
- Optional scope hidden by default.
- Controls: Start/Stop buttons for manual control (auto-start remains).

## Auto-Save
- When 240 lines complete, save image as `sstv-Robot36-YYYYMMDD-HHmmss.png` in Pictures, then reset decoder to Idle.

## Cleanup
- Remove Java `Robot36Decoder` and scope drawing logic after native path is verified.
- Keep TX at 48 kHz and export WAV at 11025 Hz (already integrated).

## Validation
- Test mic and radio RX with live SSTV signals: ensure VIS lock, line sync, and color render.
- Confirm auto-save and progressive UI match Robot36 app behavior.
- Ensure error notifications for permissions or session failures remain functional.

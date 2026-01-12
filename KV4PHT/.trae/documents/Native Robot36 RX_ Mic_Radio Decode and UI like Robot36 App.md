## Goals
- Decode Robot36 from microphone and radio reliably (VIS lock, per-line sync, color).
- Replace current Java scaffold with native decoder and show RX UI similar to Robot36 app: progressive line-by-line image, mode/VIS status, auto-save.

## Decoder Integration
- Use open-source Robot36 decoder (xdsopl/robot36) as native C/C++ library.
- Add sources under `app/src/main/cpp/robot36_decode/` with minimal dependencies.
- Update `CMakeLists.txt` to compile decoder and link to `log`, `jnigraphics`, `m`.

## JNI API
- Create `NativeSSTV` decode methods:
  - `startRobot36Decoder(int sampleRate)` → init state.
  - `feedRobot36Samples(float[]/short[] samples, int sampleRate)` → push PCM; internally resample to 11025 if decoder requires it.
  - `getRobot36FrameIfReady()` → returns a scanline or partial bitmap; called from Java to update UI progressively.
  - `stopRobot36Decoder()` → cleanup.
- Converter: bitmap creation with `AndroidBitmap_*` and generate a progressive 320×256 ARGB image.

## Capture Pipelines
- Radio: keep Visualizer on the service’s audio session; fallback to mic if session not available.
- Microphone: `AudioRecord` at 48 kHz mono float; resample to 11025 in native code when feeding decoder (simple polyphase or linear).
- Both sources push continuous PCM chunks to JNI (`feedRobot36Samples`) on a worker thread.

## RX UI (like Robot36)
- Replace/current SSTV RX UI:
  - Full-width preview area; progressive rendering per line.
  - Status strip: VIS detection ("Robot36"), line counter (e.g. `Line 123/240`), decode state (Leader/Vis/Sync/Image).
  - Start/Stop buttons for manual control; auto-start when entering SSTV tab.
  - Optional scope/spectrum minimal (can be hidden by default).
- Auto-save received image as `sstv-Robot36-YYYYMMDD-HHmmss.png` in Pictures when decode completes.

## Behavior Changes
- Enter SSTV tab → auto start RX decode for selected source; stop on leaving tab.
- When a full frame is decoded: auto-save and reset to Idle, ready for next frame.
- Notifications for errors (permissions, audio session, JNI failures).

## Timing and Robustness
- VIS lock: leader/break/leader/start + 7 bits + parity → detect Robot36.
- Per-line sync detection at 1200 Hz; align luma and chroma windows precisely.
- Cb/Cr alternation handled; YCbCr→RGB conversion as per decoder.
- Resampler: 48 kHz → 11025 Hz only in native path; TX path unchanged.

## Removal/Cleanup
- Remove Java `Robot36Decoder` class and related scope logic once native path is in place.
- Keep existing progress and auto-save logic; wire to native completion callback.

## Validation
- Test mic and radio sources with live and recorded signals.
- Confirm UI behaves like Robot36 app: progressive lines, correct mode, auto-save.
- Ensure energy visualization optional and does not affect decode.

## Notes
- NDK/CMake already configured and `jnigraphics` linked.
- If decoder requires specific sample rate (11025), ensure resampler is enabled in JNI feed.
- Maintain TX at 48 kHz; export WAV remains 11025 Hz with brainwagon encoder.

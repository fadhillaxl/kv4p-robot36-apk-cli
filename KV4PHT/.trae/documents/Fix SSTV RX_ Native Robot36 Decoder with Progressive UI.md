## Symptoms
- RX preview stays dark; scope only shows energy bars; no per-line decode like Robot36 app.

## Root Causes
- Current Java decoder is a scaffold (energy estimate + grayscale Y) without full VIS lock and per-line sync.
- Visualizer/mic feed runs at 48 kHz, but decoder logic expects strict timing; no resampler to standard decoder rate (11025).
- Chroma alternation and line alignment not implemented robustly in Java path.

## Fix Plan
### 1) Integrate Native Robot36 Decoder
- Add the open-source Robot36 decoder sources (xdsopl/robot36) under `app/src/main/cpp/robot36_decode/`.
- Update `CMakeLists.txt` to build decoder and link with `jnigraphics`, `log`, `m`.

### 2) JNI Decoder API
- Implement lifecycle and feed:
  - `startRobot36Decoder(int sampleRate)` → init decoder state.
  - `feedRobot36Samples(short[]/float[] samples, int sampleRate)` → push PCM; resample to 11025 Hz when needed.
  - `getRobot36Progress()` → returns current line index and a partial 320×256 bitmap for progressive UI.
  - `stopRobot36Decoder()` → cleanup.

### 3) Resampling and Capture
- Radio: Visualizer waveform bytes → convert to float/short → feed JNI continuously.
- Microphone: AudioRecord 48 kHz mono → feed JNI; resample internally to 11025 Hz for the decoder.
- Ensure continuous buffering (worker thread) and sufficient capture size for Visualizer.

### 4) RX UI (like Robot36)
- Replace current SSTV RX area with:
  - Full-width progressive image render updated per line.
  - Status strip: VIS mode (Robot36), state (Leader/Vis/Sync/Image), line counter `n/240`.
  - Start/Stop buttons; auto-start when entering SSTV tab.
- Hide scope by default (optional toggle), since native decoder provides robust state.

### 5) Auto-Save
- On frame completion (240 lines), save as `sstv-Robot36-YYYYMMDD-HHmmss.png` in Pictures and reset to Idle for next frame.

### 6) Cleanup
- Remove Java `Robot36Decoder` and fragile scope energy logic after native path is working.
- Keep TX path at 48 kHz; export WAV stays 11025 Hz.

### 7) Validation
- Test mic and radio sources with live SSTV; verify VIS lock and color per-line rendering.
- Confirm auto-save and status updates behave like Robot36 app.
- Add notifications for permission/session/JNI failures.

## Notes
- NDK/CMake already configured and `jnigraphics` linked; this plan focuses on native decode integration and progressive UI wiring.
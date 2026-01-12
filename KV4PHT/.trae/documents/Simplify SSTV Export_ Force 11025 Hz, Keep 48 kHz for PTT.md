## Objectives
- Use brainwagon/sstv-encoders Robot36 encoder (C) via JNI for accurate WAV export
- Export WAV always at 11025 Hz (Robot36 compatibility)
- Keep 48 kHz strictly for device PTT transmit
- Remove Export SR toggle from SSTV UI

## Steps
### 1) Add brainwagon sources
- Copy `robot36.c` (and minimal needed headers) into `app/src/main/cpp/`
- Verify license and include attribution if required

### 2) Update CMake
- Edit `app/src/main/cpp/CMakeLists.txt`:
  - `add_library(native-lib SHARED native-lib.cpp robot36.c)`
  - `find_library(log-lib log)` and `target_link_libraries(native-lib ${log-lib})`

### 3) Implement JNI
- In `native-lib.cpp`:
  - Implement `jbyteArray encodeRobot36ToWav(Bitmap bmp)`:
    - Lock bitmap pixels and convert to 320×256 RGB (Java will resize before JNI)
    - Call Robot36 encoder to produce PCM at `11025 Hz`
    - Write RIFF/WAVE header (PCM mono 16-bit, sampleRate=11025, byteRate=sr*2, blockAlign=2)
    - Return WAV as `byte[]`
  - Return `null` on failure; Java fallback remains

### 4) Wire Java
- `NativeSSTV.java`: use the JNI method
- `MainActivity.encodeImageClicked` and Save fallback:
  - Prefer `NativeSSTV.encodeRobot36ToWav(bitmap)`; if null, use existing Java encoder
- Transmit path (connected): keep current 48 kHz encoder and `sendAudioToESP32(...)`

### 5) Remove Export SR UI
- Delete Export SR section from `activity_main.xml`
- Remove handlers and `currentExportWavSampleRate` in `MainActivity`
- Encode/Save always uses `11025 Hz` for WAV

### 6) Validate
- Exported WAV decodes in Robot36 apps (VIS 0x28+parity, ~36s, correct Y/chroma timing)
- PTT transmit continues at 48 kHz
- Progress UI and notifications remain functioning

## Notes
- Ensure NDK and CMake are installed (ndkVersion pinned in build.gradle)
- If Robot36 encoder requires additional files from brainwagon repo, include them in `src/main/cpp`
- Maintain fallback to refined Java encoder to ensure robustness
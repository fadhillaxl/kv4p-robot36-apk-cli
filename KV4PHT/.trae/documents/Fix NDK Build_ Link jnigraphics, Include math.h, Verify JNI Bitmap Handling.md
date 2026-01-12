## Issues Observed
- Linker undefined symbols for `AndroidBitmap_getInfo/lockPixels/unlockPixels`: native lib not linked against `jnigraphics`.
- Warning for implicit `sin`: missing `<math.h>` include in `robot36.c`.
- Residual `currentExportWavSampleRate` reference in Java after removing Export SR toggle.

## Fix Plan
### 1) robot36.c Header
- Add `#include <math.h>` at top of `app/src/main/cpp/robot36.c` to remove implicit `sin` warning.

### 2) CMake Linking
- Edit `app/src/main/cpp/CMakeLists.txt` to link `jnigraphics`:
  - `find_library(jnigraphics-lib jnigraphics)`
  - `find_library(log-lib log)` (already present)
  - `target_link_libraries(native-lib ${jnigraphics-lib} ${log-lib} m)`

### 3) JNI Bitmap Handling
- `native-lib.cpp` already includes `android/bitmap.h` and uses AndroidBitmap APIs; keep as-is.

### 4) Force 11025 Hz Export, Remove Toggle Residues
- In `app/src/main/java/com/vagell/kv4pht/ui/MainActivity.java`:
  - Remove `currentExportWavSampleRate` field and handlers `selectExportSr48Clicked`/`selectExportSr11025Clicked`.
  - Use constant `EXPORT_WAV_SAMPLE_RATE = 11025` for all Encode/Save and non-PTT export paths.

### 5) Validation
- Rebuild CMake target to ensure native library links successfully.
- Test Encode → verify WAV decodes in Robot36 app (VIS 0x28 + parity, ~36s).
- Test Transmit → confirm 48 kHz PTT path unaffected.

## Notes
- Ensure NDK (25.2.9519653) and CMake (3.22.1) are installed.
- brainwagon C encoder is now invoked via JNI for WAV export; Java encoder remains as fallback if JNI fails.
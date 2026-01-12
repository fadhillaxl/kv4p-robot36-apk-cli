## Tujuan
- Menstabilkan decoding SSTV Robot36 dari sumber Radio dengan meresample audio RX 48 kHz menjadi 11025 Hz.
- APRS chat dan voice tetap di 48 kHz (tidak terpengaruh).

## Implementasi
### 1) Komponen Resampler
- Tambah `AudioResampler` di `com.vagell.kv4pht.sstv.robot36`:
  - Input: `float[] in`, `inRate=48000`, `outRate=11025`.
  - Output: `float[] out` dengan rasio `outRate/inRate`.
  - Algoritma: fractional-step + low-pass sederhana (memanfaatkan `Filter`/`Kaiser`) atau linear interpolation fallback.
  - Buffer reuse untuk kinerja.

### 2) Integrasi Robot36Activity (Sumber Radio saja)
- Buat decoder khusus radio dengan `sampleRate=11025`.
- Visualizer `onWaveFormDataCapture`: resample → feed decoder.
- Callback `rxAudio(samples, length)` dari `RadioAudioService`: resample → feed decoder.
- Sumber Mic/File: tetap gunakan sample rate sumber (tanpa resample), atau opsi manual “Force 11025”.

### 3) Pengaturan
- Tambah setting `SETTING_SSTV_RESAMPLE_RADIO` (default ON).
- SettingsActivity: toggle (On/Off) berada di bagian SSTV Audio.
- Robot36Activity membaca setting untuk mengaktifkan resampler.

### 4) Status UI
- Tampilkan status sumber dan laju sampel aktif: contoh `Source: Radio (48k→11.025k)` saat resampler ON.

### 5) Verifikasi
- Uji dengan audio RX live: pastikan sync/VIS terdeteksi stabil dan gambar terisi progresif.
- Uji dengan WAV 48 kHz untuk memastikan hasil konsisten.
- Pastikan APRS dan voice tidak berubah.

### 6) Kinerja
- Pastikan resampler bekerja efisien: buffer ring, minimal GC.
- Jalankan proses resample di callback, update UI di thread UI.

Setelah disetujui, saya akan menambahkan kelas resampler, mengintegrasikannya di jalur Radio, menambah toggle pengaturan, memperbarui status UI, dan menguji end-to-end.
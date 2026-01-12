## Penjelasan Masalah
- Pesan “Visualizer unavailable: use Mic or Open file” muncul ketika Visualizer gagal menempel ke `AudioTrack` RX dari `RadioAudioService`.
- Penyebab umum:
  - `AudioTrack` belum dibuat (belum ada audio RX saat Activity dibuka) → session id ≤ 0.
  - Izin `RECORD_AUDIO` belum diberikan → Visualizer tidak bisa aktif.
  - Sumber sedang memakai file (`usingFile=true`) atau Mic, sehingga pemasangan Visualizer sengaja dinonaktifkan.
  - Pemasangan dilakukan terlalu dini (bind service selesai, tetapi `audioTrack` belum aktif) → perlu menunggu callback `audioTrackCreated()`.
  - Emulator/Perangkat tidak mendukung Visualizer pada sesi saat ini.

## Rencana Perbaikan
### 1) Diagnostik & Validasi
- Pastikan pengaturan “SSTV Audio Source” = Radio.
- Pastikan izin `RECORD_AUDIO` granted.
- Tambahkan log/status saat bind: radio connected, session id saat pemasangan, dan kapan `audioTrackCreated()` dipanggil.

### 2) API Service Getter
- Tambahkan metode publik di `RadioAudioService`:
  - `public int getAudioTrackSessionId()` → mengembalikan `audioTrack != null ? audioTrack.getAudioSessionId() : 0`.

### 3) Pemasangan Visualizer Berbasis Callback
- Di `Robot36Activity` saat bind ke service:
  - Daftarkan `RadioAudioServiceCallbacks` dan override `audioTrackCreated()` untuk memanggil `attachVisualizer()` setelah `AudioTrack` aktif.
  - Jika session id masih 0, retry dengan backoff singkat (mis. 300 ms × beberapa kali) sebelum menampilkan pesan gagal.

### 4) Gating Sumber & Status
- Hanya `usingFile=true` ketika benar-benar mendecode dari file. Saat sumber = Radio, pastikan `usingFile=false` sehingga Visualizer diizinkan.
- Ubah pesan status:
  - Jika radio connected tetapi session id belum siap → “Menunggu audio RX…”.
  - Jika gagal setelah beberapa retry → “Visualizer tidak tersedia di perangkat ini; gunakan Mic atau File”.

### 5) Pengujian
- Uji di perangkat nyata: tune ke kanal dengan audio RX; pastikan Visualizer aktif dan decoder menerima aliran.
- Uji saat membuka file/Mic untuk memastikan Visualizer tidak ikut aktif.
- Uji toggle Visuals agar scope/waterfall/peak tampil di bawah gambar.

### 6) Dokumentasi Pengguna
- Tambahkan penjelasan singkat di status bar: “SSTV Source: Radio/Mic/File”, “Mode: Auto/Robot36 lock”, dan indikator saat menunggu RX.

Silakan konfirmasi. Setelah disetujui, saya akan menerapkan perubahan (getter session id di service, registrasi callback di Activity, retry pemasangan, dan penyesuaian pesan/status), lalu verifikasi end-to-end bahwa decode dari radio berjalan normal.
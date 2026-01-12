## Diagnosa Cepat
- Terdapat dua method `getAudioTrackSessionId()` di `RadioAudioService` sehingga memicu error duplikasi.
- Error `cannot find symbol getRadioType/setMinTxFreq/setMaxTxFreq` mengindikasikan Lombok `@Getter/@Setter` tidak menghasilkan method, kemungkinan karena:
  - Posisi kurung penutup kelas yang salah sehingga parser gagal sebelum Lombok memproses.
  - Anotasi `@Getter/@Setter` pada field terkait hilang atau tidak terbaca.

## Rencana Perbaikan
### 1) Rapikan Struktur Kelas
- Hapus method `getAudioTrackSessionId()` yang duplikat; sisakan satu method di dalam kelas sebelum kurung penutup `}`.
- Pastikan kurung penutup kelas berada setelah semua member, tidak ada kode yang muncul setelah `}`.

### 2) Validasi Lombok Getter/Setter
- Pastikan field berikut memiliki anotasi yang benar:
  - `@Getter private RadioModuleType radioType` → menyediakan `getRadioType()`
  - `@Setter private float minTxFreq`, `@Setter private float maxTxFreq` → menyediakan `setMinTxFreq(float)` dan `setMaxTxFreq(float)`
- Jika anotasi hilang, tambahkan kembali.
- Jika Lombok tetap bermasalah, sediakan method eksplisit:
  - `public RadioModuleType getRadioType()`
  - `public void setMinTxFreq(float v)`, `public void setMaxTxFreq(float v)`

### 3) Sinkronisasi Robot36Activity ↔ Service
- Gunakan `service.getAudioTrackSessionId()` (method tunggal) pada `Robot36Activity`.
- Pertahankan callback `audioTrackCreated()` untuk memasang Visualizer setelah RX aktif.
- Tampilkan status “Menunggu audio RX…” saat session id belum siap.

### 4) Verifikasi
- Build ulang proyek; pastikan tidak ada error duplikasi atau Lombok missing symbol.
- Buka Robot36 SSTV, pilih sumber Radio; periksa Visualizer aktif saat audio RX berjalan.

Setelah Anda konfirmasi, saya akan menerapkan perubahan struktural pada `RadioAudioService` (hapus duplikat dan perbaiki posisi kurung), memastikan anotasi Lombok pada field yang diperlukan, dan menguji binding Visualizer agar berfungsi normal.
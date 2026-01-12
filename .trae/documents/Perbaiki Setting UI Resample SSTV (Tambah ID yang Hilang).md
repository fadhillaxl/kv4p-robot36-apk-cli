## Masalah
- Build error karena `R.id.sstvResampleRadioTextView` dirujuk di `SettingsActivity`, tetapi elemen dengan ID tersebut hilang dari `activity_settings.xml` (blok UI sebelumnya terhapus karena perbaikan XML).

## Rencana Perbaikan
### 1) Tambah UI Resample di Lokasi Tepat
- Tambahkan blok dropdown “SSTV Resample (Radio → 11025)” ke dalam container yang sama dengan “SSTV Audio Source” di `activity_settings.xml` (di dalam root LinearLayout dan ScrollView yang sudah ada).
- Buat `AutoCompleteTextView` dengan `android:id="@+id/sstvResampleRadioTextView"`.
- Opsi: "On" dan "Off".

### 2) Sinkronisasi dengan SettingsActivity
- `populateSstvAudioOptions()` saat ini hanya menetapkan daftar untuk Audio Source; tambahkan daftar untuk Resample (On/Off) bila diperlukan, atau gunakan `setDropdownIfPresent` yang sudah ada agar nilai diset ketika memuat.
- Listener `attachTextView(R.id.sstvResampleRadioTextView, this::setSstvResampleRadio)` sudah ada; pastikan ID tersedia.
- Default: bila setting kosong, anggap "On".

### 3) Validasi
- Build ulang dan pastikan tidak ada error ID hilang.
- Buka Settings → pastikan dropdown tampil dan penyimpanan nilai berfungsi.
- Pastikan Robot36Activity membaca setting dan resample hanya diterapkan untuk sumber Radio.

### 4) Tidak Mengubah APRS/Voice
- Pastikan jalur APRS chat dan voice tetap 48 kHz (tidak ada referensi resample di luar Robot36Activity jalur Radio).

Setelah disetujui, saya akan menambahkan blok UI tersebut di XML (posisi yang valid), memastikan ID tersedia untuk kode SettingsActivity, dan verifikasi build serta fungsionalitas.
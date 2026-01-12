# Panduan Penggunaan Python CLI

Berikut adalah panduan instalasi dan penggunaan untuk `python-cli`.

## 1. Instalasi

Pastikan Anda sudah menginstall Python 3.10 ke atas.

**Langkah 1: Masuk ke direktori**
```bash
cd python-cli
```

**Langkah 2: Buat Virtual Environment (Disarankan)**
```bash
python3 -m venv venv
source venv/bin/activate  # Untuk macOS/Linux
# atau
# venv\Scripts\activate   # Untuk Windows
```

**Langkah 3: Install Dependensi**
Anda perlu menginstall library yang dibutuhkan. Pada macOS, Anda mungkin perlu menginstall `portaudio` terlebih dahulu untuk `sounddevice`.

```bash
# macOS (jika belum ada portaudio)
brew install portaudio

# Linux (Ubuntu/Debian)
sudo apt-get install libportaudio2

# Install paket Python
pip install -r requirements.txt
```

---

## 2. Cara Menjalankan CLI

Anda dapat menjalankan CLI dengan perintah berikut. Opsi `--port` opsional jika Anda ingin menghubungkan ke perangkat radio serial.

```bash
python cli.py --port /dev/ttyUSB0
# Atau tanpa port serial (mode audio only)
python cli.py
```

---

## 3. Panduan Perintah CLI (Interactive)

Setelah program berjalan, Anda akan masuk ke mode interaktif. Ketik perintah berikut lalu tekan Enter:

### SSTV (Slow Scan TV)
- `sstv tx <IMAGE_PATH>` : Mengirim gambar sebagai SSTV (Robot36).
  - Contoh: `sstv tx gambar.jpg`
- `sstv rx start` : Memulai penerimaan/perekaman SSTV.
- `sstv rx stop` : Menghentikan penerimaan dan menyimpan file WAV.

### APRS (Automatic Packet Reporting System)
- `aprs callsign <CALLSIGN>` : Set callsign pengirim.
  - Contoh: `aprs callsign YB0AAA`
- `aprs path <PATH>` : Set path digipeater (default: WIDE1-1,WIDE2-1).
  - Contoh: `aprs path WIDE1-1`
- `aprs send <DESTINATION> <MESSAGE>` : Mengirim pesan APRS.
  - Contoh: `aprs send APKV4P "Hello World"`

### Audio & Radio Control
- `wav tx <WAV_PATH>` : Mengirim file audio WAV mentah.
- `freq <FREQUENCY>` : Mengubah frekuensi radio (jika terhubung serial).
  - Contoh: `freq 144390000`
- `ptt on` : Mengaktifkan PTT (Push To Talk) dan mengirim nada tes 1kHz.
- `ptt off` : Mematikan PTT.

### Konfigurasi & Debug
- `monitor on` / `monitor off` : Menampilkan level audio RX di layar.
- `tx export on` / `tx export off` : Jika `on`, setiap transmisi (TX) juga akan disimpan sebagai file WAV di folder `out/tx/`.
- `device list` : Menampilkan daftar perangkat audio input/output yang tersedia.
- `device out <ID>` : Mengubah perangkat output audio (gunakan ID dari `device list`).
- `status` : Cek status koneksi.
- `quit` : Keluar dari program.

---

## 4. Tool Tambahan: Generator WAV

Jika Anda hanya ingin mengubah gambar menjadi suara SSTV (Robot36) tanpa menjalankan CLI utama:

```bash
python gen_robot36_wav.py <INPUT_IMAGE> <OUTPUT_WAV>
```
Contoh:
```bash
python gen_robot36_wav.py foto.png output_sstv.wav
```

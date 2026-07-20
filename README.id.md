# Awd TeleDrive Android 📱🚀

<p align="center">
  <img src="logo-drive.png" width="130" height="130" alt="Logo Awd TeleDrive Android">
</p>

<p align="center">
  <a href="https://github.com/putuwahyu29/awd-teledrive-android/blob/main/LICENSE">
    <img src="https://img.shields.io/badge/Lisensi-MIT-yellow.svg?style=flat-square" alt="Badge Lisensi">
  </a>
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="Badge Platform">
  <img src="https://img.shields.io/badge/Bahasa-Kotlin-purple?style=flat-square&logo=kotlin" alt="Badge Bahasa">
  <img src="https://img.shields.io/badge/Framework-Jetpack%20Compose-navy?style=flat-square&logo=jetpackcompose" alt="Badge Framework">
  <img src="https://img.shields.io/badge/Arsitektur-MVI--Clean-orange?style=flat-square" alt="Badge Arsitektur">
</p>

**Awd TeleDrive Android** adalah aplikasi Android inovatif yang mengubah akun Telegram Anda menjadi penyimpanan cloud pribadi tak terbatas yang aman. Kelola file, cadangkan media secara otomatis di latar belakang, dan nikmati tampilan direktori media Anda melalui antarmuka Material 3 modern berbasis Jetpack Compose sepenuhnya.

---

## 🌐 Ekosistem Teledrive
Aplikasi ini merupakan bagian dari ekosistem lintas platform yang dirancang untuk menjadikan Telegram sebagai penyimpanan cloud pribadi Anda:
*   **📱 [Awd TeleDrive Android](https://github.com/putuwahyu29/awd-teledrive-android)**: Manajer file dan alat pencadangan Android yang aman.
*   **💻 [Awd TeleDrive Desktop](https://github.com/putuwahyu29/awd-teledrive-desktop)**: Klien desktop Wails (Go) + React berkinerja tinggi dengan sinkronisasi dua arah, dekripsi lokal, dan Berbagi Web via Cloudflare.
*   **📸 [Awd TelePhoto Android](https://github.com/putuwahyu29/awd-telephoto-android)**: Aplikasi pendamping untuk pencadangan foto/video terenkripsi di sisi klien.

---

## 🌐 Language / Bahasa
*   [English Version (Main)](README.md)
*   [Versi Bahasa Indonesia](README.id.md)

---

## 📌 Daftar Isi
- [✨ Fitur Utama](#-fitur-utama)
- [📷 Cuplikan Layar](#-cuplikan-layar)
- [📊 Matriks Perbandingan Fitur](#-matriks-perbandingan-fitur)
- [📁 Lokasi Penyimpanan & Path](#-lokasi-penyimpanan--path)
- [🚀 Panduan Penggunaan](#-panduan-penggunaan)
  - [Prasyarat](#prasyarat)
  - [Cara Mendapatkan Kredensial API Telegram](#cara-mendapatkan-kredensial-api-telegram)
  - [Proses Login](#proses-login)
  - [Setup Pencadangan Otomatis](#setup-pencadangan-otomatis)
- [🛠️ Panduan Pengembangan (Developer)](#-panduan-pengembangan-developer)
  - [Struktur Direktori Proyek](#struktur-direktori-proyek)
  - [Membangun Aplikasi dari Source Code](#membangun-aplikasi-dari-source-code)
- [⚙️ Penyelesaian Masalah](#-penyelesaian-masalah)
- [⚠️ Disclaimer](#️-disclaimer)
- [📄 Lisensi](#-lisensi)

---

## ✨ Fitur Utama

*   **☁️ Penyimpanan Cloud Telegram Tanpa Batas**: Memanfaatkan API Telegram untuk mengunggah dan menyimpan berkas berukuran apa pun. Teknologi **Auto-Split** bawaan secara otomatis menangani file yang lebih besar dari batas 2GB.
*   **📂 Manajemen File Profesional**: Buat direktori, ubah nama item, cari katalog file, dan tandai file favorit Anda. Dukungan penuh untuk **Channel Terarsip** sebagai folder.
*   **🔄 Layanan Pencadangan Otomatis**: Sinkronisasi media secara otomatis dari folder lokal pilihan Anda ke cloud di latar belakang menggunakan WorkManager Android.
*   **🔒 Keamanan Ganda**: Kunci aplikasi dengan Master Password, Sidik Jari/Wajah (Biometrics), serta enkripsi data lokal yang aman (LazySodium).
*   **🎨 Desain Dinamis Material 3**: Antarmuka modern yang secara dinamis mengubah warna tema berdasarkan wallpaper perangkat Anda serta pengaturan mode gelap/terang sistem.

---

## 📷 Cuplikan Layar

| | | |
|:---:|:---:|:---:|
| <img src="screenhoots/home.jpg" width="230" alt="Beranda / Penjelajah File"/><br/>**Beranda / Penjelajah File** | <img src="screenhoots/preview.jpg" width="230" alt="Pratinjau & Penampil File"/><br/>**Pratinjau & Penampil File** | <img src="screenhoots/media.jpg" width="230" alt="Galeri Media"/><br/>**Galeri Media** |
| <img src="screenhoots/auto_backup.jpg" width="230" alt="Pengaturan Backup Otomatis"/><br/>**Backup Otomatis** | <img src="screenhoots/stared.jpg" width="230" alt="File Berbintang / Favorit"/><br/>**Favorit / Berbintang** | <img src="screenhoots/tranfer.jpg" width="230" alt="Antrean & Proses Transfer"/><br/>**Pengelola Transfer** |

---

## 📊 Matriks Perbandingan Fitur

| Fitur | 📱 Awd TeleDrive Android | 💻 Awd TeleDrive Desktop | 📸 Awd TelePhoto Android |
| :--- | :---: | :---: | :---: |
| **Penyimpanan Cloud Tanpa Batas** | Ya (Auto-Split untuk >2GB) | Ya (Ukuran file tidak terbatas) | Ya (Foto & Video) |
| **Manajer File & Folder** | Ya | Ya | Tampilan Galeri saja |
| **Mode Sinkronisasi / Backup** | Pencadangan folder lokal | Sinkronisasi Satu & Dua Arah | Pencadangan Foto/Video otomatis |
| **Keamanan / Enkripsi** | Master Password, Biometrik | Dekripsi AES-256 (Telephoto) | AES-256-GCM sisi Klien |
| **Berbagi Web (Publik/Lokal)** | Tidak | Ya (Cloudflare Tunnel & IP Lokal) | Tidak |
| **Integrasi Sistem Native** | WorkManager Android | System Tray, Auto-Launch (Registry) | WorkManager Android |
| **Dukungan Multi-Bahasa** | Ya (EN / ID) | Ya (EN / ID) | Ya (EN / ID) |

---

## 📁 Lokasi Penyimpanan & Path

Semua data persisten, sesi cache, dan database disimpan dengan aman di direktori khusus aplikasi Android:

*   **Konfigurasi TDLib**: Disimpan secara internal di direktori `context.filesDir` (di bawah folder cache `tdlib/`).
*   **Room Database Lokal**: `tele_drive_database` yang menyimpan hierarki folder, item favorit, dan metadata antrean sinkronisasi.
*   **Preferensi Aman**: Key lokal disimpan terenkripsi menggunakan Android Keystore System (`EncryptedSharedPreferences`).
*   **Direktori Cache Media**: `context.cacheDir` yang menampung potongan file sementara selama proses upload/download sebelum disimpan ke tujuan.

---

## 🚀 Panduan Penggunaan

### Prasyarat
*   Akun Telegram yang aktif.
*   Perangkat Android yang menjalankan Android 8.0 (API 26) atau lebih tinggi.

### Cara Mendapatkan Kredensial API Telegram
Awd TeleDrive Android membutuhkan API ID & API Hash Anda sendiri untuk terhubung ke server Telegram secara aman. Ikuti langkah mudah berikut (gratis, ~2 menit):
1. Buka situs [my.telegram.org](https://my.telegram.org/) dan masuk menggunakan nomor Telegram Anda.
2. Pilih menu **API development tools**.
3. Isi formulir pembuatan aplikasi baru (Judul dan nama singkat bebas sesuai keinginan).
4. Salin nilai **App api_id** dan **App api_hash**.
5. Masukkan nilai tersebut pada layar pengaturan di aplikasi Awd TeleDrive Android.

> [!NOTE]
> Informasi kredensial API ini disimpan sepenuhnya di perangkat lokal Anda. Aplikasi ini berkomunikasi langsung ke server resmi Telegram tanpa perantara pihak ketiga.

### Proses Login
1. Buka aplikasi dan masukkan **API ID** dan **API Hash** Anda.
2. Ketik nomor telepon Telegram Anda dalam format internasional (contoh: `+628123456789`).
3. Masukkan kode verifikasi (OTP) yang dikirim ke aplikasi Telegram atau via SMS Anda.
4. Jika akun Anda memiliki Verifikasi 2 Langkah, masukkan kata sandi 2FA Anda.

### Setup Pencadangan Otomatis
1. Masuk ke tab pengaturan **Auto Backup**.
2. Berikan izin akses media/file bagi aplikasi.
3. Pilih folder lokal (misal: Kamera, Downloads) yang ingin dicadangkan.
4. Tentukan chat atau channel Telegram tujuan.
5. Proses sinkronisasi latar belakang akan berjalan otomatis saat terhubung ke Wi-Fi.

---

## 🛠️ Panduan Pengembangan (Developer)

### Struktur Direktori Proyek
```
awd-teledrive-android/
├── app/                    # Modul Inti Aplikasi Android
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/awd/teledrive/
│   │   │   │   ├── data/        # Implementasi Database Room, API & TDLib
│   │   │   │   ├── di/          # Modul Dependency Injection (Hilt)
│   │   │   │   ├── domain/      # Logika Bisnis & Model Domain
│   │   │   │   ├── ui/          # Presentasi MVI / Halaman Compose
│   │   │   │   └── worker/      # WorkManager untuk auto-backup
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle.kts
├── gradle/                 # Konfigurasi Gradle Wrapper
├── build.gradle.kts        # Root konfigurasi build
└── settings.gradle.kts     # Registrasi modul
```

### Membangun Aplikasi dari Source Code
Pastikan Anda telah memasang Android Studio terbaru di komputer Anda.

1. **Klon Repositori**:
   ```bash
   git clone https://github.com/putuwahyu29/awd-teledrive-android.git
   ```
2. **Build dengan Gradle Wrapper**:
   Buka terminal di root direktori proyek, lalu jalankan:
   ```bash
   # Membuat APK Debug
   ./gradlew assembleDebug

   # Membuat APK Release Unsigned (cocok untuk F-Droid)
   ./gradlew assembleRelease
   ```
3. **Metadata F-Droid**:
   Proyek ini menyertakan metadata [Fastlane](https://fastlane.tools/) di direktori `fastlane/metadata/android`, yang digunakan oleh F-Droid untuk mengambil deskripsi aplikasi dan konten lokal secara otomatis.

---

## ⚙️ Penyelesaian Masalah

*   **Keterlambatan Sinkronisasi WorkManager**: Sistem Android mungkin menunda sinkronisasi latar belakang untuk menghemat baterai. Anda dapat mematikan optimasi baterai khusus untuk aplikasi Awd TeleDrive Android di pengaturan Android.
*   **Gagal Inisialisasi TDLib**: Pastikan ruang penyimpanan perangkat Anda masih mencukupi. Jika file database rusak, bersihkan data aplikasi melalui Setelan ➡️ Aplikasi ➡️ Awd TeleDrive Android ➡️ Hapus Data.
*   **Kode OTP Tidak Diterima**: Periksa kembali apakah `api_id` dan `api_hash` yang Anda masukkan sudah sama dengan yang didapat dari `my.telegram.org`.

---

## ⚠️ Disclaimer

Proyek ini adalah pengembangan sumber terbuka independen yang dibuat semata-mata untuk tujuan pembelajaran dan edukasi. Proyek ini tidak berafiliasi, dikaitkan, diizinkan, didukung oleh, atau dengan cara apa pun terhubung secara resmi dengan Telegram Messenger atau perusahaan/pihak lainnya.

Pengguna bertanggung jawab penuh atas segala tindakan dan kepatuhan terhadap Ketentuan Layanan Telegram serta hukum setempat maupun internasional yang berlaku. Pengembang tidak bertanggung jawab atas penyalahgunaan, pelanggaran kebijakan, pemblokiran akun, atau konsekuensi hukum apa pun yang timbul dari penggunaan perangkat lunak ini.

---

## 📄 Lisensi

Proyek ini dilisensikan di bawah MIT License. Lihat berkas [LICENSE](LICENSE) untuk teks lisensi selengkapnya.

---

<p align="center">Dibuat dengan ❤️ oleh <a href="mailto:aguswahyu@office.awd.my.id">I Putu Agus Wahyu Dupayana</a></p>

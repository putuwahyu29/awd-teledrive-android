# Rencana Rilis TeleDrive v1.2.0

Dokumen ini merinci langkah-langkah akhir untuk merilis aplikasi versi 1.2.0 ke GitHub dan menyiapkan pengajuan ke F-Droid.

## Proposed Changes

### 1. Update Versi Aplikasi
Meningkatkan nomor versi di build script.

#### [MODIFY] [app/build.gradle.kts](file:///F:/awd-teledrive-android/app/build.gradle.kts)
- `versionName`: "1.1.0" -> "1.2.0"
- `versionCode`: 2 -> 3

### 2. Finalisasi Metadata F-Droid
F-Droid memerlukan file resep build (`.yml`). Saya akan membuatkan draf file ini untuk Anda gunakan saat mengajukan ke repositori `fdroiddata`.

#### [NEW] [com.awd.teledrive.yml](file:///F:/awd-teledrive-android/com.awd.teledrive.yml)
File konfigurasi build untuk server F-Droid.

### 3. Otomasi Git (GitHub Release)
Saya akan melakukan serangkaian perintah Git untuk:
1. Menambahkan semua perubahan (metadata, UI refactor, changelog).
2. Membuat commit rilis.
3. Membuat tag `v1.2.0`.

## Verification Plan

### Manual Verification
- Menjalankan `./gradlew assembleRelease` sekali lagi setelah bump versi untuk memastikan biner siap.
- Memeriksa file `CHANGELOG.md` apakah sudah mencakup semua poin penting.

## User Action Required
- Setelah saya selesai melakukan push dan tagging, Anda harus membuka halaman **Releases** di GitHub dan mempublikasikan draf release yang dibuat oleh tag tersebut.
- Melakukan pengajuan (Merge Request) ke [fdroiddata](https://gitlab.com/fdroid/fdroiddata) menggunakan file `.yml` yang saya siapkan.

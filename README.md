# Bubble Translate (manga / manhua / manhwa)

Bubble melayang yang menangkap layar, OCR teks, deteksi bahasa otomatis, lalu menempelkan
terjemahan tepat di atas gelembung teks aslinya. Semua on-device (Google ML Kit).

## Cara build
1. Android Studio (Koala/Ladybug atau lebih baru) -> File > Open -> pilih folder ini.
2. Tunggu Gradle sync (wrapper dibuat otomatis), lalu Run, atau Build > Build APK(s).
3. minSdk 23 (Android 6) ... targetSdk 35. Untuk Android 16/17, naikkan compileSdk/targetSdk
   ke SDK terbaru yang terpasang di Android Studio Anda.

## Cara pakai
1. Buka app -> pilih bahasa sumber (Otomatis) & bahasa tujuan -> "Mulai Bubble".
2. Izinkan "Tampil di atas aplikasi lain", notifikasi, dan rekam layar
   (Android 14+: pilih "Seluruh layar" bila ditanya).
3. Buka aplikasi manga. Ketuk bubble = mode otomatis ON (hijau): halaman/chapter baru diterjemahkan sendiri. Ketuk lagi = OFF.
   Tekan lama bubble = terjemahkan ulang halaman saat ini.
4. Pertama kali pakai satu pasangan bahasa, model terjemahan diunduh (butuh internet, sekali saja).

## Catatan
- Deteksi otomatis memakai aksara dominan (kana = Jepang, hangul = Korea, hanzi = Mandarin).
  Jika sesekali salah, pilih bahasa sumber manual di app.
- Teks vertikal Jepang (tategaki) akurasi OCR-nya lebih rendah.
- Aplikasi yang memakai FLAG_SECURE akan tampil hitam saat ditangkap (batasan Android).
- Di HP Xiaomi/Oppo/Vivo/Samsung, matikan penghemat baterai untuk app ini agar bubble tidak dimatikan sistem.

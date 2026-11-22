# Beta

Folder untuk berkas-berkas **beta** dari aplikasi ENC-AI.
Digunakan untuk menyimpan artefak uji coba sebelum dirilis ke produksi.

Isi yang umum ada:
- `*.apk` / `*.aab`        : build APK/AAB untuk uji coba
- `changelog_beta.md`      : catatan perubahan versi beta
- `screenshot/`            : hasil screenshot uji coba
- `logs/`                  : log lintasan uji coba (logcat, error, dll.)

Cara pakai:
1. Salin build beta ke folder ini.
2. Tambah `changelog_beta.md` jika ada perubahan penting.
3. Uji coba, lalu kumpulkan hasilnya di folder ini.
4. Setelah memenuhi kriteria, kirim ke cabang/ruang rilis produksi.

# Arsitektur perbaikan Droide 1.03.2

Basis kerja adalah `Droide-1.03.2-Full-Maintenance-Source.zip`, versi `1.03.2` dengan `versionCode` 49. SHA-256 arsip awal adalah `9d86e0954052f257df93a441c44176b9ed0ab0787942b694dd824d5d17f9e6ff`. Pembandingan dengan arsip VS Code, Codex CLI dan OpenCode menghasilkan sembilan perbaikan pada alur yang sudah dimiliki Droide.

Antarmuka Compose mengirim pilihan dan menerima hasil. Editor Sora tetap menjadi pemilik buffer. Validasi akhir berlangsung di komponen yang menguasai data saat operasi dilaksanakan, seperti repositori Git, pemulih sesi atau pemilik dokumen. Cara ini membatasi perubahan pada titik kegagalan yang ditemukan.

## Batas dan tanggung jawab komponen

| Tindakan pengguna | Komponen yang bertanggung jawab | Kontrak setelah perbaikan |
|---|---|---|
| Menerapkan atau menghapus stash | `GitPanel` memilih entri, `GitManager` mengerjakan operasi, `DroideStashDrop` mengubah reflog | Apply membawa ID objek lengkap sampai ke JGit. Drop menampilkan deskripsi dan ID sebelum konfirmasi, lalu memeriksa kembali pasangan indeks dan ID saat memegang kunci ref dan reflog. Jika entri bergeser atau repo sibuk, pengguna menerima alasan dan bisa memilih ulang. |
| Melanjutkan atau mencabangkan chat lama | `SessionForkHistory` memetakan pesan lama, `AgentConversationHistory` menerbitkan hasil pada sesi pemilik | Sesi tanpa riwayat API memakai teks lama yang utuh untuk pemulihan konteks aktif dan migrasi riwayat. Riwayat API tersimpan tetap menjadi rujukan jika tersedia. Pemulihan yang kalah oleh pergantian sesi dibatalkan sebelum publikasi. |
| Memeriksa file TypeScript | `SourceFileLint` memilih konfigurasi dan compiler dari lingkungan proyek | Dengan `tsconfig.json`, API TypeScript memuat opsi, referensi dan deklarasi proyek, lalu meminta diagnostik file terpilih. File yang dikecualikan tetap diperiksa secara eksplisit. Kesalahan konfigurasi yang menghalangi pemeriksaan tetap dilaporkan. |
| Memeriksa file Go | `SourceFileLint` menentukan paket, workbench memberi nama tindakan | `go vet .` berjalan dari direktori file. Nama direktori diberikan sebagai argumen terpisah. Penemuan modul dan workspace mengikuti tool Go. Proyek tanpa modul tetap dapat memeriksa fungsi lintas berkas dalam satu paket. |
| Memformat berkas dengan Prettier | `GuestDocumentFormatter` menyusun perintah formatter | Opsi proyek yang eksplisit menang atas nilai awal editor. Jika opsi proyek kosong, pilihan editor menyediakan nilai awal. Hasil tetap mengikuti alur peninjauan formatter. |
| Mencari teks di workspace | `WorkspaceSearchReader` membaca per baris, `WorkspaceSearchBudget` mencatat batas, `FileRepository` melanjutkan penelusuran | Baris yang melebihi 65.536 unit UTF-16 dilewati seluruhnya. Setelah baris itu pencarian berlanjut, termasuk ke berkas lain. Batas per berkas hanya mengakhiri berkas tersebut. Hasil parsial menyertakan alasannya. Batas waktu, byte total dan biaya pencocokan tetap mengakhiri operasi. |
| Mengganti nama simbol atau menerapkan banyak edit LSP | `TextEditApplier` menghitung offset, `LspWorkspaceEditPreflight` memeriksa kepemilikan | Satu lintasan pada snapshot teks mencatat posisi baris yang diperlukan. Persiapan edit berjalan di dispatcher komputasi. Seluruh dokumen diperiksa kembali terhadap instance, revisi dan isi sebelum kumpulan edit diterapkan ke buffer. |
| Menjalankan Quick Fix dari panel Problems atau dialog | `ProblemsSheet` menampilkan pilihan, `IdeScreen` memiliki operasi penerapan | Kedua pintu masuk mengembalikan hasil dari operasi yang sama. Kegagalan terlihat di dialog yang masih aktif. Jika dialog atau panel sudah ditutup, workbench menampilkan pesan. Pilihan lama dan ketukan berulang diperiksa sebelum menjalankan edit. |
| Membaca daftar MCP yang panjang | Sesi stdio di `McpManager` menyimpan halaman, `McpListPages` menyiapkan keluaran untuk agen | Daftar resource, template dan prompt bisa dibaca sampai selesai dengan batas 10.000 karakter per hasil. Token `continuation` terikat ke sesi, server, protokol dan operasi. Setelah seluruh entri terkirim, `nextCursor` membuka halaman server berikutnya. |

## Keputusan yang memengaruhi integritas data

**Git.** Nama `stash@{0}` bisa berpindah saat terminal membuat stash baru. Nomor urut tetap berguna saat menampilkan reflog, sedangkan 40 digit ID mengidentifikasi isi yang dipilih. Adapter kecil di paket backend JGit memakai kunci yang dikenali Git lain. Untuk penghapusan di tengah daftar, adapter membangun ulang rantai reflog di bawah kunci. Untuk entri terakhir, JGit menangani ref longgar dan ref terkemas. Tip dan reflog adalah dua berkas terpisah. Jika proses terhenti tepat di antara penggantian keduanya, kecocokan kepala reflog menjadi syarat untuk penghapusan berikutnya. Pembunuhan proses di titik tersebut belum diuji.

**Riwayat dan editor.** Teks dari sesi lama dipulihkan pada lapisan penyimpanan. Kebijakan pemilihan konteks untuk permintaan model tetap bekerja setelah pemulihan. Demikian pula, persiapan edit LSP hanya membaca snapshot yang ditangkap oleh permintaan. Semua buffer harus lulus pemeriksaan sebelum satu pun menerima perubahan. Kolom memakai satuan UTF-16, dengan pemeriksaan pasangan surrogate dan penanganan LF, CRLF, CR serta sisipan pada posisi yang sama.

**MCP.** Satu sesi menyimpan hingga empat halaman besar yang baru dipakai. Frame dari server sudah dibatasi 1.000.000 byte oleh transport. Entri tunggal yang terlalu panjang dikirim sebagai potongan JSON berurutan dengan indeks dan offset. Pengulangan token pada potongan yang sama menghasilkan data yang sama. Jika cursor server sendiri terlalu panjang setelah pengodean, token lokal meneruskannya ke server pada permintaan berikutnya. Sesi yang berakhir atau halaman yang tergeser meminta agen memulai kembali daftar. Daftar pendek tetap memakai format hasil sebelumnya.

## Dasar perbandingan

- VS Code mengonfirmasi penghapusan stash, menyimpan diagnostik per dokumen dan menggunakan indeks baris untuk memetakan offset. Droide menambahkan pemeriksaan ID pada saat penghapusan karena terminal dapat mengubah repo yang sedang terlihat di panel. [Git](https://github.com/microsoft/vscode/blob/772b7877ccfe2ad551ba29548c4ca993e735de0d/extensions/git/src/commands.ts#L4693) · [Diagnostik](https://github.com/microsoft/vscode/blob/772b7877ccfe2ad551ba29548c4ca993e735de0d/extensions/typescript-language-features/src/languageFeatures/diagnostics.ts#L43) · [Indeks teks](https://github.com/microsoft/vscode/blob/772b7877ccfe2ad551ba29548c4ca993e735de0d/src/vs/editor/common/model/pieceTreeTextBuffer/pieceTreeBase.ts#L395)
- Codex CLI merekonstruksi sesi dari item tersimpan dan menguji cursor MCP sebagai data terstruktur. Prinsip itu dipakai untuk menguji pesan lama dan kelanjutan halaman pada Droide. [Pemulihan sesi](https://github.com/openai/codex/blob/ccf9be8cc458bd6b8336fe84d68d8893f2691a5e/codex-rs/core/src/session/rollout_reconstruction.rs#L522) · [Uji MCP](https://github.com/openai/codex/blob/ccf9be8cc458bd6b8336fe84d68d8893f2691a5e/codex-rs/core/src/tools/handlers/mcp_resource_tests.rs#L30)
- OpenCode memetakan bagian pesan tersimpan, meneruskan cursor MCP, dan menjalankan Prettier dengan konfigurasi proyek. Ketiga perilaku tersebut menjadi pembanding kontrak penyimpanan, paginasi, dan pemformatan. [Pesan](https://github.com/anomalyco/opencode/blob/aec0b9a6d8898f68f923aaf08b7306d931fd9d76/packages/opencode/src/session/message-v2.ts#L202) · [Paginasi](https://github.com/anomalyco/opencode/blob/aec0b9a6d8898f68f923aaf08b7306d931fd9d76/packages/opencode/src/mcp/catalog.ts#L18) · [Prettier](https://github.com/anomalyco/opencode/blob/aec0b9a6d8898f68f923aaf08b7306d931fd9d76/packages/opencode/src/format/formatter.ts#L72)

## Hasil uji yang membedakan versi awal dan perbaikan

| Kasus | Source awal | Source perbaikan |
|---|---|---|
| Stash A dipilih, lalu terminal menambah B | Penghapusan indeks lama mengenai B | Penghapusan ditolak, A dan B tersedia. Apply dengan ID A dapat menerapkan isi A. |
| Pesan lama 8.010 unit UTF-16 dengan emoji di batas pemotongan | Konteks aktif berhenti di 8.000, di tengah pasangan surrogate | Tampilan, konteks aktif, dan riwayat lengkap memiliki seluruh 8.010 unit. |
| File TypeScript di luar `files` memuat kesalahan tipe | Kode keluar 0 | `TS2322` pada file yang dipilih, kode keluar 2. File yang valid tetap mendapat 0 saat file tetangganya salah. |
| Dua file Go membentuk satu paket yang valid | `go vet` terhadap satu file gagal | Paket lulus, termasuk contoh dengan modul bersarang dan tanpa modul. |
| `.prettierrc` menetapkan 2 spasi, editor mengusulkan 4 | Editor menghasilkan 4 spasi | Editor dan CLI proyek menghasilkan 2 spasi. |
| Daftar MCP 128 entri melebihi batas keluaran | Entri akhir dan cursor hilang dari hasil agen | Semua entri dapat dibaca berurutan. Cursor server tersedia setelah bagian lokal terakhir. |
| Pencarian melewati `extensions/cpp/syntaxes/platform.tmLanguage.json` dari arsip VS Code | Pencarian berhenti sebelum file kontrol | File kontrol ditemukan. Hasil menandai `LINE_LENGTH_LIMIT` untuk baris yang dilewati. |
| 800 edit pada 12.000 baris di JVM Linux | Median 139,21 ms | Median 2,63 ms pada pengukuran terakhir, dengan tiga pemanasan dan tujuh pengukuran. Durasi frame Android memerlukan perangkat. |

## Verifikasi source kandidat

| Lingkungan | Hasil |
|---|---|
| Build Android | Gradle 8.13 dan Temurin 17.0.20.1+1 menyelesaikan `:app:assembleSourceDebug`. Verifikator proyek memeriksa penandatanganan debug, penyelarasan ZIP, pustaka native untuk empat ABI dan 237 aset paket. Status resminya `BUILT_DEVICE_UNVALIDATED`. |
| Gradle, JUnit dan Robolectric | **67 tes lulus** pada 14 kelas yang dipilih, dengan nol kegagalan dan nol tes dilewati. Cakupannya termasuk Git melalui `GitManager`, pemulihan sesi, sesi MCP stdio, pencarian lewat `FileRepository`, pemeriksaan dokumen LSP dan interaksi Compose saat panel Quick Fix ditutup. |
| Komponen dan alat di Linux | **81 tes JUnit lulus** dalam rangkaian komponen terpilih. Pemeriksaan perilaku memakai TypeScript 5.9.3, Go 1.25.0 dan Prettier 3.6.2. Prettier juga diuji dengan pilihan awal empat spasi dan tanpa konfigurasi proyek. |
| Kontrol lain | **47 kasus Chromium** untuk skrip DOM bawaan dan **31 tes Python** untuk dokumentasi, ekspor serta integritas dependensi lulus. Pemeriksa kebijakan dependensi dan dokumen repositori juga lulus. |
| Ekspor source-only | `tools/export_fdroid_source.py` menghasilkan ekspor dan `tools/verify_fdroid_inputs.py` memverifikasi inputnya. Seluruh 33 file aplikasi dan tes yang disentuh identik dengan ekspor. Verifikasi ini mencakup input build. |

Langkah dan prasyarat build debug tersedia di `tools/source-debug.md`. Untuk mengulang integrasi alat bahasa, `WorkspaceSourceToolsTest` menerima lokasi paket TypeScript melalui `DROIDE_TEST_TYPESCRIPT_MODULE` dan direktori binari Go melalui `DROIDE_TEST_GO_BIN`.

## Cakupan yang masih memerlukan bukti lain

Upgrade dari APK produksi, siklus hidup Android, dan interaksi WebView pada HP memerlukan perangkat serta paket dengan penandatanganan yang sesuai. Untuk pembatalan sinkronisasi folder SAF, source menunjukkan kueri belum meneruskan `CancellationSignal` ke provider. Waktu tunggu dan pelepasan kunci saat provider sedang sibuk perlu diuji dengan provider yang menghormati pembatalan dan yang mengabaikannya. Rancangan untuk jalur ini mengikat satu sinyal ke job pembaca selama kueri berlangsung. Kunci mirror tetap dipegang sampai provider benar-benar selesai. Uji penerimaan perlu membuktikan bahwa pengulangan sinkronisasi menjaga perubahan lokal dan tidak menerbitkan manifest dua kali.

Runner CLI `r64-cli-exit` secara eksplisit mendokumentasikan workspace R64 dan toolchain R44 untuk mengulang bukti historis. Audit awal memang menemukannya gagal pada checkout baru. Temuan itu berlaku pada portabilitas runner. Dialog konfirmasi stash baru menyediakan bahasa Inggris dan Indonesia. Inventarisasi serta penerjemahan seluruh antarmuka adalah pekerjaan terpisah.

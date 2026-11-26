# Rencana perbaikan setelah Droide v1.03.3

Basis kerja adalah commit rilis `f0fe4e64fa9c77bc8cec296e4939a40cf5a17312`, versi kode 50. Kandidat ini disiapkan untuk pengujian pengembang. Penentuan nomor dan publikasi rilis berikutnya dilakukan terpisah.

| Urutan | Tanggung jawab | Bukti penerimaan |
|---|---|---|
| 1 | Pulihkan kompilasi seluruh source unit test dan jalankan regresi pilihan dari checkout publik. Pisahkan log diagnostik dari APK yang lolos CI. | Gradle mengompilasi semua file tes dan mengeksekusi kasus yang dipilih. Artefak APK memerlukan hasil verifikasi sukses. |
| 2 | Editor mengganti rentang secara literal. Aturan smart typing tetap dimiliki jalur keyboard. Batasi teks yang diserialisasi oleh Android saat menyimpan state pencarian. | Uji kurung, kutip, penghapusan, Undo, dan restore input pencarian. |
| 3 | Satukan teks dan kepemilikan lampiran menurut proyek, backend, serta sesi. Simpan instruksi antrean eksternal sebelum menyatakan diterima. Pulihkan input tertunda untuk ditinjau pengguna. | Uji A–B–A, restart, kegagalan penyimpanan, serta pencegahan kirim ganda. |
| 4 | Pemeriksa TypeScript memilih konfigurasi yang memiliki file di antara project references. | Bandingkan diagnostik dengan compiler TypeScript sebelum dan sesudah build. Periksa juga file lepas dan file yang dikecualikan. |
| 5 | Pembaca provider menangani variasi SSE. Terminal ACP menghormati batas byte, menangani Unicode, dan mengurangi penyalinan log. | Uji BOM dan tiga format akhir baris. Periksa batas 1 KiB, pemotongan di tengah karakter, serta perekaman aktif dan nonaktif. |
| 6 | Teruskan pembatalan ke kueri baca SAF selama kunci sinkronisasi masih dimiliki. Gunakan cadangan Ubuntu resmi yang sudah dipin bila URL utama telah hilang. | Uji provider lambat, pembatalan, retry dan integritas data. Uji 404/410 dan verifikasi hash di kedua jalur unduhan. |

## Dasar perbandingan

VS Code meminta TypeScript mengidentifikasi proyek pemilik file. OpenCode mengikat draf ke lingkup direktori dan sesi. Codex CLI memisahkan pembacaan event SSE dan pemrosesan respons, sedangkan spesifikasi ACP menetapkan batas output terminal dalam byte. Masing-masing memberi pembanding untuk perilaku yang diuji di Droide. [VS Code](https://github.com/microsoft/vscode/blob/772b7877ccfe2ad551ba29548c4ca993e735de0d/extensions/typescript-language-features/src/tsconfig.ts#L161) · [OpenCode](https://github.com/anomalyco/opencode/blob/aec0b9a6d8898f68f923aaf08b7306d931fd9d76/packages/app/src/context/prompt-state.ts#L175) · [Codex CLI](https://github.com/openai/codex/blob/ccf9be8cc458bd6b8336fe84d68d8893f2691a5e/codex-rs/codex-api/src/sse/responses.rs#L616) · [Protokol ACP](https://agentclientprotocol.com/protocol/terminals)

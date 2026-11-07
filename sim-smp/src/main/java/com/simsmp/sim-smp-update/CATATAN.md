# SIM SMP - perubahan

File BARU (tinggal taruh di src/main/java/com/simsmp/):
- RankManager.java   - rank buatan OP, disimpan di config/sim_smp/ranks.json
- Sit.java           - /sit
- ExtraCommands.java - /sit, /rank, /jumpscare

File DIUBAH (full file + folder diffs/ kalau mau edit manual):
- ClassManager.java  - tambah resetPlayer(UUID)
- ClassCommands.java - tambah /classadmin reset <player> dan /class reset <player> (OP only)
- SidebarManager.java- baris "Player:" sekarang menampilkan rank
- SimSmp.java        - daftar RankManager, ExtraCommands, hook join/disconnect/tick, bersihin sisa kursi

PENTING: ClassManager, ClassCommands, SidebarManager, SimSmp di sini hasil dekompilasi jar,
bukan source aslimu. Kalau source aslimu beda format, pakai file di diffs/ untuk edit manual saja.

Command (OP = permission level 2, sama seperti /classadmin yang sudah ada):
- /classadmin reset <player>   atau   /class reset <player>   reset semua data class pemain
- /rank                        lihat rank sendiri (semua orang)
- /rank set <player> <warna> <nama>   contoh: /rank set Steve gold Top Donor  (nama maks 16 huruf)
- /rank clear <player>
- /sit                         duduk / berdiri (semua orang), sneak juga untuk berdiri
- /jumpscare <player>          elder-guardian scare + title BOO! + buta 2 detik

Rank tampil di chat, tab list, nametag (lewat scoreboard team vanilla) dan baris Player di sidebar.
Kalau pemain sudah masuk team scoreboard lain, team itu diganti saat dikasih rank.

Belum dites compile terhadap Minecraft 26.3 (tidak ada jarnya di sini). Kalau GitHub Actions error,
kirim log-nya. Tinggi duduk diatur di Sit.SEAT_DROP (default 0.8).

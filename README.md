# doki-exts · Tsuki edition

Manga parsers library cho **Usagi App** (và các app dùng chung cấu trúc `tsuki`) — bản migrate toàn bộ
[h0dev/doki-exts](https://github.com/h0dev/doki-exts) từ core cũ (`core-parsers`, namespace
`org.koitharu.kotatsu.parsers`) sang core mới **`com.github.UsagiApp:Tsuki`** (namespace `tsuki.site.*`),
theo đúng mô hình [dragonx943/manga-repo](https://github.com/dragonx943/manga-repo),
[Gekkoushi/plugin-source](https://github.com/Gekkoushi/plugin-source) và
[InvalidDavid/UMA](https://github.com/InvalidDavid/UMA).

Chi tiết từng source: xem [MIGRATION.md](./MIGRATION.md).

## Nội dung

- 67 manga source (key) tiếng Việt + đa ngữ, giữ nguyên roster cũ của doki-exts:
  - **30 source chỉ có ở doki-exts** — được convert thủ công sang `tsuki` (NetTruyenCO/FE/LL/SSR/UU,
    HamTruyen, NewTruyen, HentaiZ, MangaZodiac, PinkTeaComic, TruyenTranh88/Full, VietComic, HentaiVN,
    Hentai18VN, MeduHentai, ViHentai, MoonTruyen, TruyenMM, MangaBall, OioiVn, DaoMeoDen, MoeTruyen…)
  - **37 source trùng key với manga-repo** — giữ bản mới nhất từ manga-repo (đã compile-checked qua
    release của họ) để tránh nhân đôi code cũ.
- Các framework base + helper: `madara`, `wpcomics`, `liliana`, `manhwaz`, `cupfox`, `madtheme`,
  `mangaball`, `yurigarden`…

## Build

Yêu cầu: JDK 17, Android SDK (cho bước dex `d8`).

```bash
chmod +x gradlew
./gradlew buildJar        # → build/libs/doki.jar
```

Push lên GitHub (`master`) là CI (`.github/workflows/publish.yml`) tự build và tạo Release
`build/libs/doki.jar` kèm tag bằng sha commit — dán URL repo vào Usagi:
*Explore → ⋮ → Manage Sources → Manage Plugins → Import from Github*.

## Cấu trúc source

```
src/main/kotlin/tsuki/site/
├── madara/  wpcomics/  liliana/  manhwaz/  cupfox/  madtheme/  mangaball/
└── vi/  (+ daomeoden/ , moetruyen/ , yurigarden/)
```

Mỗi source khai báo `@MangaSourceParser("KEY", "Tên hiển thị", "vi")`; KSP sinh danh sách nguồn tự động.

## Ghi chú

- Khác biệt đáng chú ý khi convert: `GLOBAL_NETTRUYEN_DOMAIN` → `"nettruyen1905.com"`;
  `NhentaiWorld` → `NhentaiClub` (tránh trùng `NHENTAIWORLD` của manga-repo).
- Chưa có build/compile local xác nhận — trạng thái là "đã migrate + audit tĩnh", build xác nhận qua CI.
- Một số nguồn có thể đã chết hoặc bị chặn từ datacenter (như thường lệ với CDN Việt) — cần test trên mạng thật.

## Credits

- [UsagiApp](https://github.com/UsagiApp) — Tsuki / TsukiMix / template `plugins`
- [KotatsuApp](https://github.com/KotatsuApp) — nguồn parser gốc
- [dragonx943/manga-repo](https://github.com/dragonx943/manga-repo) · [Gekkoushi/plugin-source](https://github.com/Gekkoushi/plugin-source) · [InvalidDavid/UMA](https://github.com/InvalidDavid/UMA) — tham chiếu cấu trúc mới
- [h0dev/doki-exts](https://github.com/h0dev/doki-exts) — repo gốc

### License

GPL-3.0 — xem [LICENSE](./LICENSE).

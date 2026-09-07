# MIGRATION.md — doki-exts → cấu trúc Tsuki (Usagi)

Repo này là bản migrate toàn bộ `h0dev/doki-exts` (core cũ `com.github.UsagiApp:core-parsers:0.0.3`, namespace `org.koitharu.kotatsu.parsers`)
sang cấu trúc plugin mới dùng core **`com.github.UsagiApp:Tsuki:1.0.5`** (JitPack), namespace **`tsuki.site.*`**,
theo đúng mô hình `dragonx943/manga-repo`, `Gekkoushi/plugin-source` và `InvalidDavid/UMA`.

## Ánh xạ từng source

| Key | doki-exts (cũ) | doki-exts-tsuki (mới) | Nguồn |
|---|---|---|---|
| CMANGA | site/site/kotatsu/vi/CManga.kt | site/site/vi/CMangaParser.kt | twin · giữ bản manga-repo |
| CUUTRUYEN | site/site/kotatsu/vi/CuuTruyen.kt | site/site/vi/CuuTruyenParser.kt | twin · giữ bản manga-repo |
| DAMCONUONG | site/site/kotatsu/vi/DamCoNuong.kt | site/site/vi/DamCoNuong.kt | twin · giữ bản manga-repo |
| DAOMEODEN | site/site/tachiyomi/vi/daomeoden/DaoMeoDen.kt | site/site/vi/daomeoden/DaoMeoDen.kt | doki-only · convert thủ công |
| DOCTRUYEN3Q | site/site/kotatsu/vi/DocTruyen3Q.kt | site/site/wpcomics/vi/DocTruyen3Q.kt | twin · giữ bản manga-repo |
| DOCTRUYEN5S | site/site/liliana/vi/DocTruyen5s.kt | site/site/liliana/vi/DocTruyen5s.kt | twin · giữ bản manga-repo |
| DUALEOTRUYEN | site/site/kotatsu/vi/DuaLeoTruyen.kt | site/site/vi/DuaLeoTruyen.kt | twin · giữ bản manga-repo |
| GOCTRUYENTRANH | site/site/kotatsu/vi/GocTruyenTranh.kt | site/site/vi/GocTruyenTranh.kt | twin · giữ bản manga-repo |
| GOCTRUYENTRANHVUI | site/site/kotatsu/vi/GocTruyenTranhVui.kt | site/site/vi/GocTruyenTranhVui.kt | twin · giữ bản manga-repo |
| HAMTRUYEN | site/site/kotatsu/vi/HamTruyen.kt | site/site/wpcomics/vi/HamTruyen.kt | doki-only · convert thủ công |
| HANGTRUYEN | site/site/kotatsu/vi/HangTruyen.kt | site/site/vi/HangTruyen.kt | doki-only · convert thủ công |
| HENTAI18VN | site/site/kotatsu/vi/Hentai18VN.kt | site/site/vi/Hentai18VN.kt | doki-only · convert thủ công |
| HENTAICUBE | site/site/madara/vi/HentaiCube.kt | site/site/madara/vi/HentaiCube.kt | twin · giữ bản manga-repo |
| HENTAIVN | site/site/kotatsu/vi/HentaiVN.kt | site/site/vi/HentaiVN.kt | doki-only · convert thủ công |
| HENTAIVNBUZZ | site/site/kotatsu/vi/HentaiVnBuzz.kt | site/site/vi/HentaiVnBuzz.kt | doki-only · convert thủ công |
| HENTAIVNPLUS | site/site/madara/vi/HentaiVnPlus.kt | site/site/madara/vi/HentaiVnPlus.kt | twin · giữ bản manga-repo |
| HENTAIZ | site/site/madara/vi/HentaiZ.kt | site/site/madara/vi/HentaiZ.kt | doki-only · convert thủ công |
| LANGGEEK | site/site/kotatsu/vi/LangGeekParser.kt | site/site/vi/LangGeekParser.kt | twin · giữ bản manga-repo |
| LUOTTRUYEN | site/site/kotatsu/vi/LuotTruyen.kt | site/site/wpcomics/vi/LuotTruyen.kt | twin · giữ bản manga-repo |
| LXMANGA | site/site/kotatsu/vi/LxManga.kt | site/site/vi/LxManga.kt | twin · giữ bản manga-repo |
| MANGABALL | site/site/mangaball/vi/MangaBall.kt | site/site/mangaball/vi/MangaBall.kt | doki-only · convert thủ công |
| MANGAZODIAC | site/site/madara/vi/MangaZodiac.kt | site/site/madara/vi/MangaZodiac.kt | doki-only · convert thủ công |
| MANHWAX10 | site/site/kotatsu/vi/Manhwax10.kt | site/site/vi/Manhwax10.kt | doki-only · convert thủ công |
| MATODEX | site/site/matodex/vi/MatoDex.kt | site/site/vi/MatoDex.kt | twin · giữ bản manga-repo |
| MEDUHENTAI | site/site/kotatsu/vi/MeduHentai.kt | site/site/vi/MeduHentai.kt | doki-only · convert thủ công |
| MEHENTAI | site/site/kotatsu/vi/MeHentai.kt | site/site/manhwaz/vi/MeHentai.kt | twin · giữ bản manga-repo |
| MEHENTAIVN | site/site/kotatsu/vi/HentaiVNX.kt | site/site/wpcomics/vi/MeHentaiVN.kt | twin · giữ bản manga-repo |
| MIMIHENTAI | site/site/kotatsu/vi/MimiHentai.kt | site/site/vi/MimiHentai.kt | twin · giữ bản manga-repo |
| MOETRUYEN | site/site/tachiyomi/vi/moetruyen/MoeTruyen.kt | site/site/vi/moetruyen/MoeTruyen.kt | doki-only · convert thủ công |
| MOONTRUYEN | site/site/kotatsu/vi/MoonTruyen.kt | site/site/vi/MoonTruyen.kt | doki-only · convert thủ công |
| NETTRUYEN | site/site/kotatsu/vi/NetTruyen.kt | site/site/wpcomics/vi/NetTruyen.kt | twin · giữ bản manga-repo |
| NETTRUYEN1975 | site/site/kotatsu/vi/NetTruyen1975.kt | site/site/wpcomics/vi/NetTruyen1975.kt | twin · giữ bản manga-repo |
| NETTRUYENCO | site/site/kotatsu/vi/NetTruyenCO.kt | site/site/wpcomics/vi/NetTruyenCO.kt | doki-only · convert thủ công |
| NETTRUYENFE | site/site/kotatsu/vi/NetTruyenFE.kt | site/site/wpcomics/vi/NetTruyenFE.kt | doki-only · convert thủ công |
| NETTRUYENHE | site/site/kotatsu/vi/NetTruyenHE.kt | site/site/wpcomics/vi/NetTruyenHE.kt | twin · giữ bản manga-repo |
| NETTRUYENLL | site/site/kotatsu/vi/NetTruyenLL.kt | site/site/wpcomics/vi/NetTruyenLL.kt | doki-only · convert thủ công |
| NETTRUYENSSR | site/site/kotatsu/vi/NetTruyenSSR.kt | site/site/wpcomics/vi/NetTruyenSSR.kt | doki-only · convert thủ công |
| NETTRUYENUU | site/site/kotatsu/vi/NetTruyenUU.kt | site/site/wpcomics/vi/NetTruyenUU.kt | doki-only · convert thủ công |
| NETTRUYENVIE | site/site/kotatsu/vi/NetTruyenVie.kt | site/site/wpcomics/vi/NetTruyenVie.kt | twin · giữ bản manga-repo |
| NETTRUYENX | site/site/kotatsu/vi/NetTruyenX.kt | site/site/wpcomics/vi/NetTruyenX.kt | twin · giữ bản manga-repo |
| NEWTRUYEN | site/site/kotatsu/vi/NewTruyen.kt | site/site/wpcomics/vi/NewTruyen.kt | doki-only · convert thủ công |
| NHATTRUYENVN | site/site/kotatsu/vi/NhatTruyenVN.kt | site/site/wpcomics/vi/NhatTruyenVN.kt | twin · giữ bản manga-repo |
| NHENTAICLUB | site/site/kotatsu/vi/NhentaiWorld.kt | site/site/vi/NhentaiClub.kt | doki-only · convert thủ công |
| OIOIVN | site/site/cupfox/vi/OioiVn.kt | site/site/cupfox/vi/OioiVn.kt | doki-only · convert thủ công |
| OTRUYEN | site/site/kotatsu/vi/OTruyenParser.kt | site/site/vi/OTruyenParser.kt | twin · giữ bản manga-repo |
| PINKTEACOMIC | site/site/madara/vi/PinkTeaComic.kt | site/site/madara/vi/PinkTeaComic.kt | doki-only · convert thủ công |
| QUAANHDAOCUTEO | site/site/madara/vi/Quaanhdaocuteo.kt | site/site/madara/vi/Quaanhdaocuteo.kt | twin · giữ bản manga-repo |
| RUAHAPCHANHDAY | site/site/madara/vi/RuaHapChanhDay.kt | site/site/madara/vi/RuaHapChanhDay.kt | twin · giữ bản manga-repo |
| SAYHENTAI | site/site/kotatsu/vi/SayHentai.kt | site/site/manhwaz/vi/SayHentai.kt | twin · giữ bản manga-repo |
| THIENTHAITRUYEN | site/site/kotatsu/vi/ThienThaiTruyen.kt | site/site/vi/ThienThaiTruyen.kt | doki-only · convert thủ công |
| TOPTRUYEN | site/site/kotatsu/vi/TopTruyen.kt | site/site/wpcomics/vi/TopTruyen.kt | twin · giữ bản manga-repo |
| TRUYENGG | site/site/kotatsu/vi/TruyenGG.kt | site/site/vi/TruyenGG.kt | twin · giữ bản manga-repo |
| TRUYENHENTAI18 | site/site/kotatsu/vi/TruyenHentai18.kt | site/site/vi/TruyenHentai18.kt | twin · giữ bản manga-repo |
| TRUYENHENTAIVN | site/site/kotatsu/vi/TruyenHentaiVN.kt | site/site/vi/TruyenHentaiVN.kt | twin · giữ bản manga-repo |
| TRUYENHENTAIZ | site/site/kotatsu/vi/TruyenHentaiZ.kt | site/site/vi/TruyenHentaiZ.kt | doki-only · convert thủ công |
| TRUYENMM | site/site/kotatsu/vi/TruyenMM.kt | site/site/vi/TruyenMM.kt | doki-only · convert thủ công |
| TRUYENQQ | site/site/kotatsu/vi/TruyenQQ.kt | site/site/vi/TruyenQQ.kt | twin · giữ bản manga-repo |
| TRUYENTRANH3Q | site/site/kotatsu/vi/TruyenTranh3Q.kt | site/site/vi/TruyenTranh3Q.kt | doki-only · convert thủ công |
| TRUYENTRANH88 | site/site/madara/vi/TruyenTranh88.kt | site/site/madara/vi/TruyenTranh88.kt | doki-only · convert thủ công |
| TRUYENTRANHDAMMYY | site/site/madara/vi/TruyenTranhDamMyy.kt | site/site/madara/vi/TruyenTranhDamMyy.kt | twin · giữ bản manga-repo |
| TRUYENTRANHFULL | site/site/madara/vi/TruyenTranhFull.kt | site/site/madara/vi/TruyenTranhFull.kt | doki-only · convert thủ công |
| TRUYENVN | site/site/madara/vi/TruyenVn.kt | site/site/madara/vi/TruyenVn.kt | twin · giữ bản manga-repo |
| VCOMYCS | site/site/kotatsu/vi/VcomycsParser.kt | site/site/vi/VcomycsParser.kt | twin · giữ bản manga-repo |
| VIETCOMIC | site/site/madara/vi/VietComic.kt | site/site/madara/vi/VietComic.kt | doki-only · convert thủ công |
| VIHENTAI | site/site/kotatsu/vi/ViHentai.kt | site/site/vi/ViHentai.kt | doki-only · convert thủ công |
| YURIGARDEN | site/site/kotatsu/vi/yurigarden/YuriGarden.kt | site/site/vi/yurigarden/YuriGarden.kt | twin · giữ bản manga-repo |
| YURIGARDEN_R18 | site/site/kotatsu/vi/yurigarden/YuriGardenR18.kt | site/site/vi/yurigarden/YuriGardenR18.kt | twin · giữ bản manga-repo |

## Các thay đổi cấu trúc quan trọng

- Hạ tầng build (settings/buildSrc/plugins-ksp/gradle wrapper/libs.versions.toml) lấy nguyên bộ từ manga-repo — bộ đã chứng minh build được với Tsuki 1.0.5 + KSP + d8 dex. Artifact = `build/libs/doki.jar`; CI `.github/workflows/publish.yml` tự build & tạo GitHub Release theo commit sha khi push vào `master`.
- Mọi package `org.koitharu.kotatsu.parsers.*` → `tsuki.*`; `site.<family>.<lang>` → `tsuki.site.*`. Bỏ segment `kotatsu`/`tachiyomi` khỏi package (theo convention manga-repo).
- 37 source đã có bản twin trong manga-repo (cùng key) → giữ nguyên bản manga-repo (đã compile-checked qua release của họ), tránh nhân đôi code cũ lỗi thời.
- 30 source chỉ có ở doki + các base/helper (CupFoxParser, MadThemeParser, MangaBallFilters, DaoMeoDenFilters, MoeTruyenFilters, ImageDecryptor, ViHentaiPacker) → convert thủ công: đổi package/import, giữ nguyên logic.
- `GLOBAL_NETTRUYEN_DOMAIN` (const trong WpComicsParser cũ, đã bị manga bỏ) → thay bằng literal `"nettruyen1905.com"` trong NetTruyenFE/LL/SSR/UU.
- `NhentaiWorld` (key NHENTAICLUB, domain nhentaiclub.icu) → rename `NhentaiClub` cho khỏi lẫn với `NHENTAIWORLD` (nhentaiclub.space) của manga-repo.
- `MadThemeParser` không có source con nào dùng (orphan từ bản cũ) — giữ nguyên như repo gốc.
- Bỏ `src/test` (test cũ dựa core-parsers) và thư mục `org/` chứa `.class` compile dư trong repo cũ.

## Trạng thái verify

Không build local được trong sandbox (JVM bị chặn bởi PRoot). Kiểm chứng tĩnh đã làm:
- 0 tham chiếu `org.koitharu` còn sót trong `src/main/kotlin`;
- package khai báo khớp 100% đường dẫn thư mục;
- 67 source-key unique, không trùng, đúng bằng roster cũ;
- mọi `override` của file converted đều tồn tại trong base framework mới (manga) / Tsuki core.
→ Bước xác nhận cuối: push lên GitHub, CI `buildJar` sẽ compile; nếu lỗi nhỏ về API sẽ sửa theo log của workflow.

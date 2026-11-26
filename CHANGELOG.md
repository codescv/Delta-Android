# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Version 2.0.5 tracked the iOS Delta release this port is based on; later
versions are Android-only updates.

## [2.2.0] - 2026-11-26

### Added

- **App language setting (Settings › Display › Language)**: Choose **System Default** (`跟随系统` / `System Default` / `システムに従う`), **中文**, **English**, or **日本語**. The default is System Default: `zh*` → Simplified Chinese, `ja*` → Japanese, anything else → English. The choice is saved in `preferences.json` and applies immediately, with no app restart.
- **Full Japanese localization** (`values-ja` + in-app string catalog).

### Changed

- **Each UI language is now fully single-language**: Settings, Library, context menus, pause menu, save states, the save conflict picker, cheats, Delta Sync / Google OAuth, updater, feedback, and error/toast messages are localized consistently across English, Simplified Chinese and Japanese. Mixed CN/EN strings such as `Support / 更新与反馈` and `检查更新 (Check for Updates)` are gone, and so is the split where Chinese devices showed some Settings sections in Chinese and others in English.
- Proper nouns are kept as-is: console and core names, BIOS filenames, WFC servers, cheat formats, Google Drive, Delta Sync, RetroAchievements, and Epilogue Operator.

## [2.1.0] - 2026-11-20

### Added

- **Google Drive Save Sync (cross-platform with Delta iOS)**: Pure-Kotlin OAuth 2.0 PKCE loopback login (`drive.file` scope) with Android Keystore AES-GCM encrypted credential storage and Drive REST v3 3-way MD5-deduped sync under `My Drive / Delta Sync/games/<system>_<crc32>/` (`save.srm`, `states_index.json`, `states/<id>.svs|.png`, `meta.json`).
- **Interactive Save Conflict Picker (`Ask Every Time`)**: New default conflict resolution policy that prompts with a side-by-side **This Device** vs **Google Drive** comparison dialog (`Keep This Device` / `Use Google Drive` / `Later`), with unseen-cloud-change (`StaleConflictException`) protection, automatic `.conflict-*.bak` local backups, stale auto-state invalidation when cloud SRAM wins, and running-game safety guards.
- **Settings › Delta Sync**: Full Google Drive sign-in/out, GCP OAuth setup (pre-filled Desktop Client ID), `Sync Now` status, `Auto-Sync` (foreground resume + game exit), `Sync Battery Saves (.srm)`, `Sync Save States (.svs)`, conflict policy selector, and pending-conflicts banner.

## [2.0.10] - 2026-11-19

### Fixed

- **D-Pad press animation alignment**: Keep standalone D-Pad artwork centered inside its recessed skin cutout with alpha-masked (`SrcAtop`) directional 3D lighting, and remove extra floating chevrons / stroke borders so pressed feedback aligns cleanly across all `.deltaskin` and procedural skins.

## [2.0.9] - 2026-11-19

### Changed

- **Directional D-Pad press animation**: Pressing Up, Down, Left, Right, or diagonals on the D-Pad now produces distinct directional rocker tilt, per-arm shading/highlight, gliding radial glow, and animated directional arrows on both official `.deltaskin` skins (with or without a separate D-Pad asset) and built-in procedural console skins instead of a uniform highlight/scale.

## [2.0.8] - 2026-11-12

### Changed

- Kept **检查更新 / Check for Updates** and **反馈给 Agent / Send Feedback** exclusively in
  **Settings** (top **Support / 更新与反馈** section) and removed the duplicate rows from the
  in-game Pause › **…** (`MoreOptionsSheet`) menu.

## [2.0.7] - 2026-11-12

### Changed

- **检查更新 / Check for Updates** and **反馈给 Agent / Send Feedback** now live in a new
  **更新与反馈 / Support** section at the very top of Settings (with blue/purple icon badges,
  the installed version and live update status), and also in the in-game Pause › **…** sheet.
- The update check now runs silently on every cold start (no 24-hour lockout, matching
  Lemuroid) and again when the app returns to the foreground after 5+ minutes. A silent
  check that finds no update now shows "已是最新版本（x.y.z）/ Up to date (x.y.z)" instead of
  leaving the row idle.
- Full Simplified Chinese (`zh` / `zh-rCN`) translations for the updater and Agent feedback
  UI; the default (English) labels are bilingual so both languages are always visible.

### Fixed

- Installing a downloaded update now resumes automatically after granting "Install unknown
  apps" even if Android killed and restarted Delta in the meantime (the pending-install flag is
  persisted).

## [2.0.6] - 2026-11-12

### Fixed

- **LCD Grid** video filter rewritten as a verbatim port of Lemuroid's
  (LibretroDroid `ShaderConfig.LCD`) shader. The grid is now uniform: one soft cell
  per game pixel, no moiré and no RGB subpixel colour fringes. Edge sharpness and grid
  strength scale with the on-screen pixel size (`screenDensity`), so the grid fades out
  on very small screens instead of beating.

## [2.0.5] - 2026-11-12

Initial Android release: a port of Delta 2.0.5 for iOS.

### Added

- Native emulation engine (`libdelta_core.so`) hosting libretro cores for
  Game Boy Advance (vba-next), Game Boy / Game Boy Color (gambatte),
  NES (QuickNES), SNES (snes9x2005), Nintendo 64 (parallel-n64),
  Nintendo DS (melonDS) and Sega Genesis / Mega Drive (Genesis Plus GX).
- Game library with ROM and ZIP import, folder scanning, CRC32 identification
  and automatic box art from the OpenVGDB index.
- Delta's dark UI rebuilt in Jetpack Compose: library grid, pause menu,
  settings, alternate app icons.
- `.deltaskin` controller skin engine: bundled Delta skins for every system,
  skin import, portrait/landscape and edge-to-edge layouts, controller-skin
  switching per system.
- Multi-touch gamepad with sliding between buttons, chorded inputs via
  extended edges, D-pad sectors with dead zone, analog stick and C-button
  support for N64, touch-screen input for Nintendo DS, and haptic feedback.
- Save states: auto (rotating), quick, general and locked slots, compatible
  with the iOS `.svs` container format; in-game battery saves.
- Cheat codes (Action Replay, GameShark, Game Genie, Code Breaker and raw
  formats where supported by the core).
- Physical gamepad support with per-player button mapping.
- Fast forward, screenshots, audio output via `AudioTrack`, and experimental
  feature toggles matching Delta 2.0.5.
- In-app updates from GitHub Releases with SHA-256 checksum, package name,
  version code and APK signature verification.
- Agent feedback e-mail composer from Settings.
- GitHub Actions CI (unit tests, debug build, content gates) and tagged
  release workflow publishing signed universal and per-ABI APKs with
  `SHA256SUMS.txt`.

[2.0.6]: https://github.com/codescv/Delta-Android/releases/tag/v2.0.6
[2.0.5]: https://github.com/codescv/Delta-Android/releases/tag/v2.0.5

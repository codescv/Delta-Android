# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Version numbers track the iOS Delta release this port is based on.

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

[2.0.5]: https://github.com/codescv/Delta-Android/releases/tag/v2.0.5

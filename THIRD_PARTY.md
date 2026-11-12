# Third-party components

Delta for Android as a whole is distributed under the
[GNU AGPL v3.0](LICENSE). It bundles or is derived from the components below,
each of which remains under its own license. Licenses for the emulator cores
were verified against the license headers / `copyright` files of the sources
vendored under `app/src/main/cpp/cores/`.

## Emulator cores (`app/src/main/cpp/cores/`)

| Component | Used for | License | Upstream |
|---|---|---|---|
| vba-next | Game Boy Advance | GPL-2.0-or-later (Blargg sound modules LGPL-2.1-or-later) | https://github.com/libretro/vba-next |
| gambatte | Game Boy / Game Boy Color | GPL-2.0 (`libgambatte`); MIT (libretro resampler glue) | https://github.com/libretro/gambatte-libretro |
| QuickNES (`nes_emu`) | NES | LGPL-2.1-or-later (some files GPL-2.0-or-later) | https://github.com/libretro/QuickNES_Core |
| snes9x2005 | Super Nintendo | Snes9x license (non-commercial use only); libretro port glue MIT; ndssfc-derived parts GPL-2.0-or-later — see `cores/snes9x2005/copyright` | https://github.com/libretro/snes9x2005 |
| Genesis Plus GX | Sega Genesis / Mega Drive | Genesis Plus license (non-commercial, MAME-style source redistribution terms); some sound / CPU modules BSD or LGPL-2.1 | https://github.com/libretro/Genesis-Plus-GX |
| melonDS | Nintendo DS | GPL-3.0-or-later | https://github.com/melonDS-emu/melonDS |
| parallel-n64 — mupen64plus-core, rsp-hle, libretro glue | Nintendo 64 | GPL-2.0-or-later | https://github.com/libretro/parallel-n64 |
| parallel-n64 — angrylion RDP (`mupen64plus-video-angrylion`) | N64 RDP rendering | BSD-3-Clause | https://github.com/libretro/parallel-n64 |
| parallel-n64 — cxd4 RSP (`mupen64plus-rsp-cxd4`) | N64 RSP (LLE) | CC0-1.0 (public domain dedication) | https://github.com/libretro/parallel-n64 |
| libretro-common | File / string / VFS helpers shared by the cores | MIT | https://github.com/libretro/libretro-common |

> **Note:** snes9x2005 and Genesis Plus GX prohibit commercial use and sale.
> Delta for Android is distributed at no cost; anyone redistributing it must
> respect those terms.

## Application code, assets and data

| Component | Used for | License | Upstream |
|---|---|---|---|
| Delta (iOS) by Riley Testut | UI design, `.deltaskin` controller skins, artwork and app-icon assets, save-state format | AGPL-3.0 | https://github.com/rileytestut/Delta |
| DeltaCore (iOS) by Riley Testut | `.deltaskin` format and skin layout semantics | AGPL-3.0 | https://github.com/rileytestut/DeltaCore |
| Lemuroid (fork) | In-app GitHub update and feedback module (ported) | GPL-3.0 | https://github.com/Swordfish90/Lemuroid |
| OpenVGDB | Box-art index (`assets/OpenVGDB/openvgdb_boxart.tsv`) | MIT | https://github.com/OpenVGDB/OpenVGDB |

## Libraries

| Component | License | Upstream |
|---|---|---|
| AndroidX (Core, Activity, Lifecycle, DocumentFile) | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Jetpack Compose & Material 3 | Apache-2.0 | https://developer.android.com/jetpack/compose |
| Kotlin standard library | Apache-2.0 | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| Coil | Apache-2.0 | https://github.com/coil-kt/coil |

Test-only dependencies (e.g. JUnit) are not shipped in the APK.

No ROMs or BIOS images are distributed with this project.

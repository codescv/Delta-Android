# Delta for Android

Delta for Android is an Android port of **Delta 2.0.5**, the all-in-one
classic video game emulator for iOS by Riley Testut. It recreates Delta's
library, controller skins, pause menu and settings on Android, and runs the
emulation cores natively through a libretro host.

## Features

- **Systems**
  | System | Core |
  |---|---|
  | Game Boy Advance | vba-next |
  | Game Boy / Game Boy Color | gambatte |
  | Nintendo Entertainment System | QuickNES |
  | Super Nintendo | snes9x2005 |
  | Nintendo 64 | parallel-n64 |
  | Nintendo DS | melonDS |
  | Sega Genesis / Mega Drive | Genesis Plus GX |
- **Controller skins** – full `.deltaskin` support (bundled Delta skins for
  every system, import your own), portrait / landscape / edge-to-edge layouts.
- **Multi-touch input** – slide between buttons, chorded presses, D-pad with
  dead zone, N64 analog stick and C-buttons, DS touch screen, haptics.
- **Save states** – auto (rotating), quick, general and locked slots;
  compatible with Delta's `.svs` format.
- **Cheats** – Action Replay, GameShark, Game Genie, Code Breaker and raw codes
  where the core supports them.
- **Box art** – automatic cover art lookup via the OpenVGDB index.
- **Game controllers** – Bluetooth / USB gamepads with per-player mapping.
- **In-app updates** – checks GitHub Releases and verifies each download's
  SHA-256 checksum, package name, version code and APK signature before
  installing.
- **Agent feedback** – compose a feedback e-mail (with device and app details)
  directly from Settings.

## Requirements

- Android 8.0 (API 26) or newer
- An `arm64-v8a` or `x86_64` device or emulator

## Building

Prerequisites:

| Tool | Version |
|---|---|
| JDK | 21 |
| Android SDK platform / build-tools | 35 / 35.0.0 |
| Android NDK | 27.0.12077973 |
| CMake (from the SDK) | 3.22.1 |

```bash
# Debug APK -> app/build/outputs/apk/debug/
./gradlew assembleDebug

# JVM unit tests (also builds and runs the native host JNI library)
./gradlew testDebugUnitTest

# Content gates run in CI
bash tools/ci_gates.sh
```

Point Gradle at your SDK with `ANDROID_HOME` or a `local.properties` file
containing `sdk.dir=/path/to/Android/Sdk`.

## Release builds and signing

`./gradlew assembleRelease` signs with a release keystore when these
environment variables are set, and falls back to the debug keystore otherwise:

| Variable | Meaning |
|---|---|
| `DELTA_KEYSTORE` | Path to the keystore file |
| `DELTA_KEYSTORE_PASSWORD` | Keystore password |
| `DELTA_KEY_ALIAS` | Key alias |
| `DELTA_KEY_PASSWORD` | Key password |

The [release workflow](.github/workflows/release.yml) runs on `v*` tags. It
decodes the base64 keystore from the `DELTA_KEYSTORE_B64` repository secret
(plus the three password/alias secrets above), builds the release APKs and
publishes `Delta-Android-v<version>.apk`, per-ABI APKs and `SHA256SUMS.txt`
to GitHub Releases, using the matching section of
[CHANGELOG.md](CHANGELOG.md) as release notes.

## ROMs and BIOS files

This project does **not** include any games, ROMs or BIOS files, and never
downloads them. Only play games you legally own and have dumped yourself.

## License

Delta for Android is licensed under the
[GNU Affero General Public License v3.0](LICENSE), the same license as Delta
for iOS. Bundled emulator cores and other components keep their own licenses;
see [THIRD_PARTY.md](THIRD_PARTY.md). Some cores (snes9x2005, Genesis Plus GX)
forbid commercial redistribution.

This port is an independent community project and is not affiliated with or
endorsed by the original Delta developers or by Nintendo, Sega or any other
platform holder.

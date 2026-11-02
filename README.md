<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/source/tiviman-wordmark-dark.svg">
    <img src="docs/brand/source/tiviman-wordmark-light.svg" alt="Tiviman" width="320">
  </picture>
</p>

<p align="center">
  Open-source IPTV player for Android TV, built around the programme guide.
</p>

<p align="center">
  <a href="https://github.com/faisalnh/tiviman/actions/workflows/ci.yml"><img src="https://github.com/faisalnh/tiviman/actions/workflows/ci.yml/badge.svg" alt="CI status"></a>
  <a href="https://github.com/faisalnh/tiviman/releases/latest"><img src="https://img.shields.io/github/v/release/faisalnh/tiviman?include_prereleases&sort=semver" alt="Latest release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/faisalnh/tiviman" alt="License: GPL-3.0"></a>
</p>

Tiviman plays your own live-TV playlist on Android TV. It opens on a programme guide, not a channel list, and every screen is built for the D-pad remote: Left opens groups, OK tunes, and a second OK goes fullscreen.

Tiviman ships no channels, subscriptions, or provider accounts. You add a playlist you already have the right to use.

> [!NOTE]
> Tiviman is in early development (0.x). Automated and emulator checks pass, but testing with real remotes on physical TVs is still in progress. Expect rough edges and report them in [Issues](https://github.com/faisalnh/tiviman/issues).

## What it does

**Sources**

- M3U playlists by URL, Xtream Codes accounts, and Stalker portals (live TV only)
- Several saved playlists, with rename, reorder, and a combined search across all of them
- Credentials and catalogs are stored encrypted with an Android Keystore key

**Guide**

- XMLTV import from a URL, from your Xtream account, or from the guide address declared in an M3U file
- 24 hours of stored schedule to browse and search, with manual channel mapping when names don't match
- Optional refresh every six hours

**Player**

- HLS, DASH, and progressive streams through Media3 ExoPlayer
- Video, audio, and caption track selection, display modes, and a sleep timer
- History, Favorites, and channel-name search; Left and Right switch channels in fullscreen
- Picture-in-picture on devices that support it
- Short foreground recordings of direct streams, two-stream multiview, and an experimental audio delay

Each feature has documented limits. Recording, for example, is capped at 30 minutes per file and doesn't cover HLS. See [feature limits](docs/feature-batch.md) and [supported provider flows](docs/playlist-setup.md).

## Install

You need an Android TV or Google TV device running Android 6.0 or later.

1. Download `tiviman-<version>.apk` from the [latest release](https://github.com/faisalnh/tiviman/releases/latest).
2. Check it against the `.sha256` file published next to it.
3. Sideload it, either with a file manager on the TV or over ADB:

   ```sh
   adb install tiviman-0.1.0.apk
   ```

4. Open **Tiviman** from the launcher. It starts with sample guide data until you add a playlist.
5. Press the remote's Menu key, or open groups with Left and choose **Playlist setup…**, then pick **M3U playlist**, **Xtream Codes**, or **Stalker Portal**.

Release APKs are all signed with the same key, so new versions install over old ones and keep your data.

## Privacy

- No analytics, ads, crash reporters, or accounts. The only dependencies are AndroidX RecyclerView and Media3.
- The app connects only to the playlist, stream, and guide addresses you enter or choose.
- Saved playlists and provider credentials are encrypted on the device and excluded from Android backup.
- Setup screens block ordinary screenshots.
- HTTP is allowed because most IPTV providers only serve HTTP. Over HTTP, your playlist address and any login in it travel unencrypted.

Details are in [playlist setup and privacy](docs/playlist-setup.md#privacy-boundaries).

## Build from source

Requirements: JDK 17 and the Android SDK (platform 35). The Gradle wrapper downloads everything else.

```sh
git clone https://github.com/faisalnh/tiviman.git
cd tiviman
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew :app:assembleRelease` builds an unsigned release APK. Signed builds and the release process are described in [releasing](docs/releasing.md).

On-device instrumentation checks use synthetic data and a custom runner. Run them only on a test device or emulator with no real playlist saved. See [development](docs/prototype.md).

## Documentation

| Topic | Document |
| --- | --- |
| Playlist sources, limits, and privacy | [docs/playlist-setup.md](docs/playlist-setup.md) |
| Programme guide import and mapping | [docs/epg.md](docs/epg.md) |
| Multiple playlists | [docs/multi-playlist.md](docs/multi-playlist.md) |
| Recording, multiview, sleep timer, and other limits | [docs/feature-batch.md](docs/feature-batch.md) |
| Development, code layout, and tests | [docs/prototype.md](docs/prototype.md) |
| Device testing checklist | [docs/human-testing.md](docs/human-testing.md) |
| Interface design | [design.md](design.md), [docs/core-ui-spec.md](docs/core-ui-spec.md), [docs/ui-research.md](docs/ui-research.md) |
| Brand | [docs/brand.md](docs/brand.md), [docs/brand/brand-update.md](docs/brand/brand-update.md) |
| Versioning and releases | [docs/releasing.md](docs/releasing.md) |

## Contributing

Bug reports with your TV model and Android version are the most useful contribution right now. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first. Never paste playlist URLs, credentials, MAC addresses, or unreviewed screenshots into an issue.

Security problems go through [SECURITY.md](SECURITY.md), not public issues.

## License

Tiviman is free software, licensed under the [GNU General Public License v3.0](LICENSE). You can use, study, share, and change it; if you distribute a modified version, you must publish its source under the same license.

Copyright (C) 2026 Faisal Nur Hidayat

The wordmark font, Atkinson Hyperlegible Next, is used under the [SIL Open Font License](docs/brand/fonts/OFL.txt).

Tiviman is not affiliated with any IPTV provider and does not supply content.

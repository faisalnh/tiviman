# Playlist setup

Tiviman supports live TV only. VOD, series, and catch-up aren't supported.

## Adding a playlist

Press the remote's Menu key, or open groups with Left and choose **Playlist setup…**. Then:

1. Choose **M3U playlist**, **Xtream Codes**, or **Stalker Portal**, and press **Next**.
2. Enter the fields for that type.
3. Wait while Tiviman downloads and checks the channel list. It shows the number of channels found.
4. Press **Done** to open the guide.

Back or Cancel at any step keeps your existing playlists unchanged.

| Type | What to enter | What Tiviman does |
| --- | --- | --- |
| M3U playlist | The full URL of an extended M3U file | Downloads it and reads channels and groups |
| Xtream Codes | Server address (with port or path if needed), username, password | Signs in through `player_api.php`, loads live categories and channels, plays `/live/<user>/<password>/<id>.ts` |
| Stalker Portal | Portal URL and a MAC address you're authorised to use | Performs the handshake, loads genres and all channels, and requests a fresh stream link each time you tune or retry |

For Xtream, enter the server address only, not a `get.php` or `player_api.php` URL. For Stalker, enter a `/c/` portal URL, the portal base, or the `server/load.php` endpoint; a `/c/` URL maps to `/server/load.php`. Tiviman doesn't generate or copy device identities.

## Limits

| Source | Limit |
| --- | --- |
| M3U | 8 MiB file, 20,000 channels, 16,384 characters per line |
| Xtream | 32 MiB per response, 100,000 channels |
| Stalker | 32 MiB per response, 20,000 channels |

M3U support:

- `#EXTM3U` and `#EXTINF` entries, UTF-8 with or without BOM, absolute HTTP and HTTPS stream URLs. Groups come from `group-title`.
- Credentials in the query string work. Credentials in the address (`user:pass@host`) and fragments are rejected.
- Custom stream headers, `|` options, VLC or Kodi options, DRM, and non-HTTP stream URLs are rejected rather than silently ignored.
- An HLS media playlist is a stream, not a channel list, and can't be imported as one.

Xtream builds MPEG-TS live URLs. Stalker supports MAC, cookie, and bearer sessions with MAG-style client headers; device ID, serial, or signature authentication and extra playback headers aren't supported. A Stalker channel list that comes back paginated or truncated is rejected rather than imported incomplete.

Streams play through Media3 with HLS, DASH, and progressive support. Whether a particular provider, codec, or device works isn't guaranteed.

## Privacy

- Playlists, channel lists, and provider settings are encrypted with AES-GCM using a non-exportable Android Keystore key, in app-private storage excluded from backup. This doesn't protect against a rooted or compromised device.
- The M3U download URL is used for the download and not saved. Xtream and Stalker server addresses, credentials, and MAC addresses are saved encrypted, because playback needs them. Session tokens and resolved stream links stay in memory.
- Address, username, and MAC fields show plain text so you can check what you typed. The Xtream password is masked; **Show password** reveals it until focus leaves the field. These fields ask the keyboard not to learn what you type, but third-party keyboards may ignore that.
- Setup and channel screens block ordinary screenshots (`FLAG_SECURE`). Accessibility services and developer tools can still read the screen.
- Error messages don't include URLs, server responses, or exception text, and Media3 logging is turned off before playback. Android system and network logs can't be guaranteed free of credentials, so don't share raw device logs.
- HTTP and HTTPS are both accepted because most IPTV providers only serve HTTP. Over HTTP, the URL and any login in it travel unencrypted. Tiviman's own downloads never follow an HTTPS-to-HTTP redirect, and signed-in provider requests never follow a redirect to another host. Media playback may follow its own redirects.
- Cancelling stops an in-progress download. An import that has already finished saving can't be undone with Back.

## Testing with synthetic data

The on-device checks use local synthetic servers and refuse to run when a real playlist is saved. Run them only on an emulator or test device without your own playlists:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e playlist true faisalnh.tiviman.test/faisalnh.tiviman.PrototypeChecks
adb shell am instrument -w -e providers true faisalnh.tiviman.test/faisalnh.tiviman.PrototypeChecks
```

See [development](prototype.md) for the full list of checks.

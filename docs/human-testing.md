# Device testing checklist

Automated checks run on an emulator with synthetic data. They can't tell whether focus is visible from the sofa, whether audio is in sync, or whether a real provider's streams play. This checklist covers that, using a real TV and remote.

Run it before each stable release. Record PASS, FAIL, or NOT TESTED for each item, using the template at the end.

## Before you start

- Use a playlist you have the right to use, and test one familiar channel first.
- Track, display, and Favorites changes are saved. Note your original choices so you can restore them.
- Respect your provider's connection limits. Close playback in other apps first.
- Keep screenshots and channel or provider names out of public reports.

## Install and launch

1. Install the release APK over the previous release. Saved playlists should still be there.
2. On the launcher, the banner shows the full Tiviman wordmark, and the icon shows the lowercase `t` with the blue cape. Nothing is clipped.
3. Cold launch shows the wordmark, then the guide.
4. With animations turned off in developer options, the wordmark doesn't pulse or fade.
5. With TalkBack on, the launch screen announces "Tiviman. Loading saved playlist".

## Guide

1. Up and Down move focus without tuning. Left at the first programme opens groups.
2. OK on another channel tunes it in the preview. OK on the tuned channel opens fullscreen.
3. Channels with no guide data show `No information`.
4. Focus is always visible, including from across the room.

## Fullscreen

1. OK opens the programme panel. Down opens the toolbar. All 14 toolbar items can be reached, and the focused item stays visible.
2. Up returns to the panel; Down returns to the last toolbar item.
3. Back hides the controls; Back again returns to the guide.
4. With controls hidden, Left and Right switch channels within the group and stop at the first and last channel.
5. Picking a channel from Favorites keeps quick switching inside Favorites.
6. Text is readable over bright video.

## Player tools

1. On a stream with several audio or caption tracks, switching changes what you hear or read.
2. Display modes change the picture without retuning.
3. Sleep timer: set 15 minutes and wait. Playback stops; the TV stays on.
4. Picture-in-picture (if your TV supports it): only the current stream continues, and expanding returns to fullscreen on the same channel.
5. Audio delay (optional): a positive delay is audible; set it back to 0 ms afterwards.
6. Watch one stream for 20 to 30 minutes. Note any stalls, audio drift, or restarts.

## Setup and settings

1. Playlist setup: type with the TV's own keyboard, then press Back without importing. Existing playlists are unchanged.
2. Settings → Playlists: switching playlists doesn't start playback until you choose a channel.
3. All playlists: search with the TV keyboard and press Back without playing.
4. Settings → EPG: import a guide and check current and next titles and times for a known channel against your time zone.
5. Recordings and Multiview open and cancel cleanly.
6. Home, then return: the same channel, no duplicate audio.

## Report template

```text
Tiviman version:
TV model / Android version / remote:
Section and step:
PASS / FAIL / NOT TESTED:
Keys pressed:
Expected:
Actual (no private details):
Reproducible?:
```

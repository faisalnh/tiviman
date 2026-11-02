# Feature limits

What each feature does, and where it stops. Limits come from the code; if this page and the code disagree, the code is right and this page needs fixing.

## Recordings

Explicit, foreground recording of direct progressive or MPEG-TS streams over HTTP or HTTPS.

- At most 30 minutes or 256 MiB per recording, and ten saved recordings.
- Needs at least 320 MiB of free space to start.
- HLS, DASH, and Stalker streams can't be recorded. There's no background or scheduled recording.
- Recordings are stored in app-private storage but aren't encrypted.
- Leaving the screen stops recording and playback. Saved recordings can be played and deleted.

## Multiview

Two streams at once, with one audible at a time. You can switch which one has audio, and both are released when you leave. Whether your provider allows two connections, and whether your TV can decode two streams, depends on them.

## Audio delay (experimental)

Delays audio by 0 to 2000 ms for the current session only, to correct audio that runs ahead of the picture. Changing it may reconnect the stream. Negative offsets, passthrough, and audio offload aren't supported.

## Sleep timer

Stops playback (not the TV) after 15, 30, 45, 60, 90, 120, 180, or 240 minutes. It survives leaving the app, but not the app being closed by the system or a reboot. Choosing the same channel afterwards stays stopped; Play or another channel resumes.

## Display modes

Normal, Full, Zoom, Zoom 2.35:1, Reduced, 4:3, 16:9, and 2.35:1. The choice is saved per channel.

## Picture-in-picture

Available from the toolbar and Settings on Android 8.0+ devices that support it. Pressing Home doesn't enter picture-in-picture automatically.

## Playlist sources

See [playlist setup](playlist-setup.md) for M3U, Xtream Codes, and Stalker limits, and [programme guide](epg.md) for XMLTV limits.

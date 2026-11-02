# Programme guide (XMLTV)

Tiviman shows `No information` for a channel until you import a guide. It never invents programme data for your channels.

## Importing a guide

Open **Settings → EPG** and choose one of:

- An XMLTV URL, plain or gzip-compressed. Compression is detected from the file contents, not the name.
- Your saved Xtream account's guide. No credentials need to be entered again.
- A guide address declared in an M3U file (`x-tvg-url`, `url-tvg`, or `tvg-url`, up to four). Tiviman lists them but downloads nothing until you choose one.

A failed import keeps the previous guide. Each playlist has its own guide; importing for one playlist doesn't change another.

## Matching channels

1. Your manual mapping for the channel, if set.
2. The channel's `tvg-id` (M3U) or `epg_channel_id` (Xtream), matched exactly.
3. Otherwise, a unique XMLTV display name, ignoring case.

Ambiguous or unmatched channels show `No information`. Fix them in **Settings → EPG** by searching the stored schedule and assigning an XMLTV channel ID. Clearing a mapping returns that channel to automatic matching.

Times with a UTC offset are honoured; times without one are read as UTC. Labels use the device time zone.

## Refresh

Automatic refresh is off by default. When turned on for a playlist, Android's job scheduler refreshes the guide about every six hours. Android decides the exact timing, and the job doesn't survive a reboot until Tiviman is opened again. A failed refresh keeps the previous guide.

## Limits

| Limit | Value |
| --- | --- |
| Download size | 32 MiB |
| Size after decompression | 512 MiB |
| Programmes scanned | 1,000,000 |
| Programmes kept | 100,000 in total, up to 48 per channel, within the next 48 hours |

Large guides keep fewer programmes per channel, nearest first, so every matched channel keeps its current programme. Files with a DTD or external entities are rejected. Individual programmes with a missing ID or title, or a duration that is zero, negative, or longer than seven days, are skipped; malformed XML aborts the whole import.

Guide downloads accept HTTP and HTTPS. An HTTPS address never redirects to HTTP, and redirects to a different host are refused, except a public, query-free `github.com` address redirecting to `raw.githubusercontent.com`.

Stalker portal guide APIs aren't supported. Use an XMLTV URL instead.

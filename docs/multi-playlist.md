# Multiple saved playlists

Open **Settings → Playlists** or press the remote's Menu key. The list shows your saved playlists and marks the active one.

- **Select** a playlist to open its guide. Nothing plays until you choose a channel or press Play.
- **Add playlist** opens M3U, Xtream Codes, or Stalker Portal setup and creates a new named playlist. A successful import makes it active; a failed import leaves the current playlist unchanged.
- **Manage** lets you rename, move up or down, and delete. The active playlist and the last remaining playlist can't be deleted.
- **All playlists** searches channels across every playlist, showing up to 200 results with current and next programmes. Choose a result to play it. Stalker channels can't be played from this screen.

Up to 32 playlists, with names up to 80 characters.

## What each playlist keeps separately

Each playlist has its own encrypted catalog, guide, favorites, history, navigation position, and track and display choices. Changing one never affects another. The playlist list itself stores only local IDs, names, and which one is active, not provider addresses.

New playlists are stored under `no_backup/playlists/<id>/` in app-private storage. Selecting a playlist never contacts its provider.

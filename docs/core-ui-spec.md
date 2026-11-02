# Tiviman core UI specification

This document defines Tiviman's guide and player state contract. [design.md](../design.md) defines the visual direction and records implemented geometry; [prototype.md](prototype.md) records implementation history and validation limits.

## State model

Maintain separate state for:

- selected navigation destination;
- selected group and focused group;
- tuned channel and focused channel;
- focused programme and shared visible time window;
- fullscreen, guide, groups and expanded navigation presentation;
- player overlay visibility and focused action;
- toolbar focus memory;
- settings drawer and its navigation stack.

Selecting or focusing a programme must not automatically tune its channel.

## Guide and programme behaviour

- The guide has a live preview at upper left, programme details at upper right, a time ruler and a vertically scrolling channel/programme grid.
- Programme width varies with duration on a shared time scale. The ruler and all programme rows scroll together; the channel identity column stays anchored.
- The implemented guide uses a 39 dp row pitch and 320 dp per hour, with half-hour ruler labels. See design.md section 15 for geometry adjustments.
- Up/Down moves channel focus without tuning. Left/Right moves programme focus.
- At the current-window left boundary, Left opens groups. Left from groups can expose navigation.
- Center on a different channel's current programme tunes it in preview and keeps the guide visible. Center on the tuned channel's current programme opens fullscreen.
- Future and past programmes remain focusable information, without tuning or misleading click semantics. Current-programme membership is start-inclusive and end-exclusive.
- Back from fullscreen restores the guide. Held Back from the guide can return to fullscreen; ordinary Back remains sufficient for navigation.
- Missing EPG shows `No information`. Mixed populated and missing rows are supported; private catalogs never receive invented programme data.
- Current programme details can show start/end, progress and remaining minutes. Future details do not show current progress.
- Short cells may truncate a title; the details header provides the full title and wrapped description.
- Focus fill is light with dark text. Tuned-channel marker and current-time cursor are independent of focus. Blue channel text alone must not be treated as proof of tuning.
- Logos, when available, occupy fixed slots and never change row height.

Use invented channels, programme names and local test media in fixtures. Do not copy private playlist metadata into documentation or tests.

## Fullscreen navigation contract

- OK opens the programme information panel with channel identity, current/next information and available media badges.
- Panel actions are TV guide, History and Schedule, with Retry only after failure.
- Down reveals a separate horizontally scrolling toolbar. The panel collapses into a non-focusable programme strip.
- Left/Right traverses the toolbar and keeps its focused action visible. Up returns to the panel; Down again restores the remembered toolbar item.
- Back dismisses panel or toolbar to hidden-controls video. Another Back returns to the guide. Reopening the panel starts at TV guide.
- With controls hidden, Left/Right switches within the active group in catalog order, without wrapping.
- Media Play/Pause follows player capability; there is no separate Pause toolbar tile.
- Overlay inactivity timeout is a chosen seven seconds. It is suspended for reading details, dialogs, drawers, schedule, errors and accessibility reading.
- Opening and closing menus must not tune another channel or replace the playback session.

Toolbar order:

1. Search
2. Channels list
3. Recordings
4. Multiview
5. Picture-in-picture
6. Video tracks
7. Audio tracks
8. Audio offset
9. Closed captions
10. Display mode
11. Sleep timer
12. Add to Favorites (or Remove from Favorites)
13. Channel options
14. Settings

Enabled actions must have working backends. Unsupported actions need an explicit unavailable state, not an enabled no-op. Track labels come from actual stream metadata. Channel options provides playback-choice summaries, a channel-only reset and the favorite toggle. Display policies, timer durations and feature limits are documented in prototype.md and [feature-batch.md](feature-batch.md).

## Settings and schedule routes

- Schedule opens an information-only overlay for the tuned channel. Focusing or activating a programme there never tunes. Back restores the Schedule action.
- Toolbar Settings opens Quick settings over video. Back restores the toolbar Settings item.
- Quick settings All settings, the guide rail gear and the Menu key open full Settings without video. Back restores the originating control.
- Guide glass panels sit side by side over a static backdrop. A covered glass panel recedes to opaque rather than stacking glass effects.
- Programme cells retain duration-based width and do not scale on focus.

## Validation still requiring people or devices

Physical D-pad traversal, TalkBack speech, TV keyboard use, viewing-distance readability, large-font clipping, audible track/caption behaviour, sustained playback and device/provider compatibility remain bounded acceptance tasks. See [human-testing.md](human-testing.md). Synthetic checks do not establish universal compatibility or release readiness.

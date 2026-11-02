# Tiviman UI research and validation

## Scope

Tiviman is a native Android TV IPTV player with a guide-first layout and D-pad navigation. This document tracks design decisions and validation work, not a claim that every screen or device has been tested.

The state and navigation contract is in [core-ui-spec.md](core-ui-spec.md). Visual direction, tokens and implementation deviations are in [design.md](../design.md). Code layout and test commands are in [development](prototype.md).

## Design decisions

- Keep the live preview and focused programme details above a shared channel/time grid.
- Keep focus, selected group, tuned channel and current time distinct.
- Use duration-based programme widths, anchored channel identities and half-hour ruler labels.
- Keep group and navigation panes beside the guide; expanding them pushes the panels rather than covering them.
- Use a light focus surface with dark text and a restrained blue accent for time and tuned-channel state.
- Use native Android views, recycled rows and system sans-serif with system font scaling.
- Separate the fullscreen programme panel from the scrolling toolbar. Preserve Up/Down focus memory and Back restoration.
- Show missing programme information honestly and expose only working or explicitly unavailable actions.

## Visual direction

The owner approved five generated concepts (C1–C5) on 2026-10-30. They are design targets, not runtime captures or measurements. They are not checked into the repository.

The Liquid Glass treatment uses side-by-side panels over a static guide backdrop and tinted player controls over video. Covered panels recede rather than stacking glass. Full Settings has no video. Android blur support varies; in-window controls use tinted glass, not sampled video frames.

The implemented guide retains a 39 dp row pitch and 320 dp/hour scale. The 30 dp pitch in the concept proposal was not adopted. See design.md section 15 for the implemented 224 dp identity column and other adjustments. Tokens and timings are Tiviman design choices; calculated contrast is not device validation.

The current identity is the owner's integrated lowercase `t` wordmark and blue cape. [brand.md](brand.md) covers name, voice, colour and typography; [brand/brand-update.md](brand/brand-update.md) defines the current logo.

## Validation boundaries

JVM tests and synthetic on-device checks cover navigation, storage, parsing and player behaviour on an emulator. They don't replace testing on a real TV.

Still requiring human/device acceptance:

- physical remote traversal and focus restoration across screens;
- TalkBack announcements and TV keyboard entry;
- guide, toolbar and form readability at viewing distance and enlarged system fonts;
- bright-video contrast with the actual material tier;
- audible tracks, captions, lip-sync and actual timer expiry;
- sustained playback, real-device PiP and provider/decoder limits;
- launcher banner/icon appearance and intended startup splash behaviour.

Use the [device testing checklist](human-testing.md). Do not enable provider refresh, start extra private streams or change accounts merely to fill a checklist.

## Capture policy

Use synthetic catalogs and local media for shared screenshots. Keep private playlist, channel, programme and account metadata out of public notes. Raw captures belong under ignored `private/` and require privacy review before sharing.

ADB screenshots and UI hierarchies are sequential, not atomic. Capture overhead makes them unsuitable for precise animation or overlay timing. Record device density and logical dimensions when measuring geometry; a 1920 × 1080 image does not imply one fixed dp canvas on every TV.

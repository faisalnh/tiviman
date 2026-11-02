# Tiviman TV interface: Liquid Glass revision

Status: implemented in the prototype (2026-10-30, steps 1–6); emulator-validated with synthetic fixtures; human remote acceptance pending. See [implementation notes](#15-implementation-notes-2026-10-30).
Date: 2026-10-30 (revises the 2026-10-29 proposal).
Direction: preserve Tiviman's guide-first workflow and documented remote behaviour; restyle the app with an Android translation of Apple's Liquid Glass material. This remains a native Android TV interface, not a tvOS port or a web dashboard. The user explicitly requested the Liquid Glass direction; it is applied as a material and focus language, not as a copy of Apple screens or assets.

## 1. Evidence and scope

### Product scope

Tiviman keeps a guide-first layout: preview at upper left, programme details at upper right, a shared duration-based timeline and anchored channel identities. Focus and tuning remain separate. Fullscreen OK opens a programme panel; Down opens a scrolling toolbar.

The [core UI specification](docs/core-ui-spec.md) defines the navigation contract. [UI research](docs/ui-research.md) tracks validation boundaries. Values in this document are Tiviman design choices.

### Liquid Glass sources

Read 2026-10-30, public Apple developer documentation only:

- *Adopting Liquid Glass*, `developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass`.
- Human Interface Guidelines, *Materials*, `developer.apple.com/design/human-interface-guidelines/materials`.

Principles taken from those pages (paraphrased), and how this design applies them:

| Apple guidance | Meaning for this app |
| --- | --- |
| Liquid Glass is a functional layer for controls and navigation that floats above content | Rail, player strip and toolbar, quick settings, sheets and overlays are glass floating over video or the guide backdrop |
| Avoid Liquid Glass in the content layer | Deliberate, user-approved exception: the guide's information panels are glass over a **static** backdrop (section 6). Programme cells inside them are not separate glass, and nothing moves behind them |
| Use it sparingly; do not layer glass on glass | Glass panels may sit side by side at one level; a panel never stacks on another glass panel. A covered panel recedes to opaque |
| Regular variant for text-heavy components | Guide panels, channel schedule, quick settings, settings sidebar, sheets |
| Clear variant over rich media, with a 35% dark dimming layer over bright content | Only the fullscreen toolbar capsule, always with the dimming layer |
| Controls take on Liquid Glass when focused (tvOS) | Focus is the "lit lens": a bright glassy fill on the focused element |
| Group toolbar items by function; label every icon | The 14-item toolbar keeps its order and gains visual group dividers |
| Concentric shapes; inset sheets let content peek through | Floating, inset panels with derived corner radii |
| Scroll edge effect keeps controls legible | Fading edges where lists scroll beneath headers |
| Sheets/action sheets originate from their source | Option sheets grow from the toolbar item or row that opened them |
| Full-height sheets become more opaque to keep focus on the task | Full Settings is a dedicated screen with no video behind it |
| Reduce transparency, increased contrast and reduced motion change the effect | Opaque and reduced-motion variants are full designs |

Apple's APIs (SwiftUI/UIKit glass effects, system blur materials, SF Pro, SF Symbols) are unavailable on Android and are not used. No Apple images, fonts, icons or exact material recipes are copied.

### Approved concept images

On 2026-10-30 the user approved five generated concept images as the visual target. They were produced from prompts derived from this document. They are **not** runtime captures or measurements, and they are not checked in to the repository. If stored later, keep them under `docs/design-concepts/` and mark them as generated concepts.

| Concept | Screen | Section |
| --- | --- | --- |
| C1 | TV guide with glass rail, details card, ruler strip and grid panel | 8.1 |
| C2 | Channel schedule overlay over the playing channel | 8.2 |
| C3 | Fullscreen player: programme strip above the glass toolbar | 8.3 |
| C4 | Quick settings drawer over video, toolbar receded below | 8.4 |
| C5 | Full Settings screen, no video, glass sidebar | 8.5 |

The concepts show real backdrop blur through every panel and a video-coloured ambient backdrop behind the guide. Section 6 records what Android can actually do; where the two differ, this document wins.

### Interpretation rule

Everything below is a proposed design choice unless explicitly labeled observed. Values are starting tokens for the prototype, not Apple specifications. Contrast figures in section 6 are calculated from the proposed tokens, not measured on a device.

Section 15 records where the implementation deviates from this proposal. See [development](docs/prototype.md) for the code layout.

## 2. Design character

Reading: a guide-first native Android TV application for viewers using a D-pad remote, in a dark cinematic style where information and controls sit on liquid glass over live video or a calm backdrop, dial **ENERGY 2 / RHYTHM 2 / MOTION 2**.

The repeated identity has three parts:

1. The anchored channel column and precise shared timeline .
2. The **lit lens**: a light focus surface, drawn as a bright glassy fill with a specular top edge, moving through dark glass.
3. A single blue current-time line and progress fill, the only saturated color on guide and player screens.

| Decision | Purpose |
| --- | --- |
| Glass panels side by side, never stacked | Distinctive material across the app without glass-on-glass muddiness |
| Static backdrop behind guide glass | Guide text stays readable because nothing moves behind it |
| Overlays over the playing channel | The viewer keeps watching while checking the schedule or changing playback options |
| Full Settings without video | Configuration tasks get the viewer's full attention |
| Lit lens focus | Makes the light focus surface the focal point at viewing distance |
| Floating inset surfaces with concentric radii | Content peeks around controls; shapes feel related |
| Blue accent kept rare | Identifies time and tuned-channel state; glass is never tinted blue |
| Native Android sans-serif | Android language coverage and user font settings without licensed Apple fonts |
| Short, interruptible morphing transitions | Shows where a sheet came from without delaying repeated remote input |

## 3. Structure and navigation

Existing UX is kept. Planned navigation order is Search, TV, Movies, Recordings, My list, with Settings near the bottom. Expose a destination only when its screen and behavior exist; a planned destination must not become a dead enabled control.

### Layers

| Level | Contents | Material |
| --- | --- | --- |
| Backdrop | Video, or the static guide backdrop (`canvas` plus a fixed dark ambient gradient) | None |
| Glass level | Guide: rail, details card, ruler strip, grid panel. Player: programme panel or strip, toolbar capsule. Overlays: channel schedule, quick settings drawer. Full Settings: sidebar | Glass, side by side, never stacked |
| Elevated | Option sheets, confirmation sheets, status capsule | Glass; everything beneath recedes |

**No stacking rule.** When a panel opens over another glass panel, the covered panel recedes: its tint becomes opaque, it dims by about 30%, and it loses its shadow. Example C4: quick settings opens over the video while the toolbar below recedes. Panels that sit beside each other (the guide panels, or the programme strip above the toolbar) are all live glass. The lit lens sits inside its panel and does not count as a panel.

### Navigation map additions

These are the only new routes. Every existing key path stays.

| From | Action | Opens | Back returns to |
| --- | --- | --- | --- |
| Fullscreen programme panel | Center on a new **Schedule** action next to TV guide and History | Channel schedule overlay (8.2) for the tuned channel | Programme panel, Schedule action focused |
| Fullscreen toolbar | Center on **Settings** (existing entry 14) | Quick settings drawer (8.4); replaces the current minimal Settings dialog | Toolbar, Settings item focused |
| Quick settings | Center on **All settings** | Full Settings screen (8.5); playback stops showing | Quick settings, All settings row focused, same channel |
| Guide rail | Center on the Settings gear | Full Settings screen | Guide, rail gear focused |
| Remote Menu/Settings key (currently opens playlist setup) | Press | Full Settings, Playlists category (same playlist chooser content) | Previous screen |

Keep these states independent: destination, selected/focused group, tuned/focused channel, focused programme, shared time window, visible pane, overlay focus, toolbar focus memory, quick settings row, full Settings category and page stack. Focus changes update information; only explicit activation may tune.

## 4. Layout system

### Coordinate convention

Use Android `dp` for layout and `sp` for text. The earlier emulator's 1920 × 1080 image corresponded to approximately 960 × 540 dp at its reported density. Do not treat every 1080p television as that density or hard-code physical-pixel coordinates.

Proposed starting layout at 960 × 540 dp:

| Region | Proposed dimensions | Reason |
| --- | --- | --- |
| Edge safe inset | 24 dp horizontal, 16 dp vertical | Keep key information away from television edges |
| Gap between side-by-side glass panels | 8 dp | Panels read as separate pieces of one glass level |
| Guide rail | 48 dp glass capsule, inset 12 dp left, full safe height | Retains the documented compact footprint |
| Expanded navigation / group pane | 220 dp, extends the rail capsule | Recognizable browsing without a new navigation model |
| Guide preview | 288 × 162 dp, 16:9, 12 dp radius | Live context; video is not glass |
| Guide details card | Remaining header width × 162 dp | Focused programme title, time, progress, description |
| Guide ruler strip | Full grid width × 24 dp capsule | Time labels on their own glass so the grid header stays clean |
| Guide grid panel | Full grid width, to the bottom safe inset | Channel identities and programme cells |
| Channel identity column | 128 dp inside the grid panel | Number and name; play marker hangs in the left padding |
| Programme row pitch | 30 dp (28 dp cell, 2 dp gap) at default font scale | Seven rows visible beneath the header in C1 |
| Time scale | 150 dp per 30 minutes | Duration relationships visible across the panel |
| Channel schedule panel | 60% of width, inset 16 dp top/left/bottom | Video stays visible on the right 40% |
| Player programme strip | Inset width × about 60 dp, 8 dp above the toolbar | Compact identity/progress above the controls (C3) |
| Player toolbar capsule | Inset width × 64 dp, 16 dp above the bottom | Floating control bar; scrolls horizontally when needed |
| Quick settings drawer | 300 dp wide, inset 16 dp top/right, ends 8 dp above the toolbar | Playback options beside the video (C4) |
| Full Settings sidebar | 260 dp wide, inset 24 dp top/left/bottom | Category list (C5) |
| Full Settings content | Remaining width, 32 dp gutter from the sidebar | Category page on opaque rows |
| Choice/confirmation sheet | 480 dp max width, anchored to its source or centered | Decisions stay near the control that caused them |

These dimensions are fitted to the approved concepts. The proposed 30 dp guide row pitch is tighter than the implemented 39 dp pitch and the earlier 44 dp proposal. It must be validated at real viewing distance and at large font scales before it is adopted; if titles clip, increase the pitch and show fewer rows. The inset drawer and panels are deliberate design choices.

Use a spacing scale of 4, 8, 12, 16, 24 and 32 dp. Programme gaps are 2 dp because duration, not card spacing, carries information. Toolbar function groups are separated by a 1 dp vertical divider with 12 dp space on each side.

At narrower logical widths or larger system fonts, reduce visible rows and the visible timeline duration before shrinking text. Allow the details card to grow, keep a 16:9 preview, and make panels scroll. At higher resolutions, derive dimensions from logical size rather than doubling values blindly.

### Guide composition (C1)

```text
 ╭──╮ ┌──────────────────┐ ╭───────────────────────────────────╮
 │⌕ │ │ live preview     │ │ Northern Coastlines               │
 │▣ │ │ 16:9 video       │ │ 19:00 – 20:00 · 104 Horizon Nature│
 │▤ │ │                  │ │ ━━━━━━━━━━━──────  24 min left    │
 │◉ │ └──────────────────┘ │ description …                     │
 │⌑ │                      ╰───────────────────────────────────╯
 │  │ ╭────────────────────── 18:30 ── 19:00 ── 19:30 ── 20:00 ─╮
 │  │ ╰───────────────────────────────────●─────────────────────╯
 │  │ ╭─────────────────┬──────────────────┃────────────────────╮
 │  │ │ 101 Metro News  │ cell     │ cell  ┃         │ cell     │
 │  │ │▶104 Horizon …   │ cell  │░ LIT LENS ┃░░░░│ cell         │
 │⚙ │ │ 106 Jazz FM TV  │ No information   ┃                    │
 ╰──╯ ╰─────────────────┴──────────────────┃────────────────────╯
 glass rail · glass card · glass ruler · glass grid over static backdrop
```

The focused programme is the main focal point. The preview is solid video without a decorative frame or glow. The details card shows the full title when a short cell truncates it.

## 5. Visual tokens

All values in this section are design tokens. Implemented deviations are recorded in section 15.

### Color

| Token | Value | Use |
| --- | --- | --- |
| `canvas` | `#101216` | Base backdrop |
| `ambient` | Fixed radial gradient, `#1E2A36` at 40% fading to `canvas`, upper-right and lower-left | Static guide backdrop so glass has depth; not derived from video |
| `surface` | `#1C2027` | Glass tint; opaque rows in full Settings; opaque fallback |
| `surfaceRaised` | `#292F38` | Channel-number tile, text fields, receded panels, opaque sheet fallback |
| `fillQuiet` | White at 6% | Programme cells and rows inside glass panels |
| `fillQuietStrong` | White at 10% | Cells in the focused channel's row; selected but unfocused tabs/items |
| `textPrimary` | `#F5F7FA` | Titles and ordinary labels |
| `textSecondary` | `#B8C0CC` | Supporting metadata; never implies disabled |
| `textPast` | `#B8C0CC` for titles | Past programmes in the channel schedule, plus their earlier times |
| `accent` | `#77B8FF` | Current-time line and label, tuned marker, progress fill, "Now", Active state |
| `lens` | `#F5F7FA` at 94% | Focused programme, row, tab or action |
| `lensText` | `#101216` | Text and icons on the lens |
| `scrim` | Black at 30% (overlays on video), 45% (sheets) | Keeps the video recognizable but calmer behind panels |

Palette: neutral greys plus one blue accent. Error meaning comes from text and an icon, not a new color. Focus, selected group, tuned channel, and the current time stay distinguishable without color: lens fill and shape, retained raised fill, play marker plus "Playing" text, and a line plus time label.

### Glass material

| Token | Recipe | Use |
| --- | --- | --- |
| `glassRegular` | `surface` at 78% over a 28 dp backdrop blur | Panels that are their own window on blur-capable devices |
| `glassRegularFlat` | `surface` at 88%, no blur | In-window panels and devices without blur (the common case) |
| `glassClear` | 35% black dimming layer, then `surface` at 55% | Player toolbar capsule only |
| `glassRim` | 1 dp stroke: white 22% on the top edge, 10% on the sides, 4% on the bottom | The bright liquid rim seen in the concepts; never used as focus |
| `glassSpecular` | 1 dp inner highlight 2 dp below the top edge, white 12%, fading out toward the corners | The thick-glass top highlight |
| `glassSheen` | Vertical gradient, white 6% → 0% across the top 35% | Static light from above; never moves |
| `glassShadow` | Black 45%, 28 dp blur, 10 dp downward offset | Lifts live glass off the backdrop; receded panels drop it |
| `lensSpecular` | 1 dp white 60% highlight on the lens top edge | Gives the focused element the glassy finish in the concepts |
| `receded` | `surfaceRaised` opaque, content dimmed 30%, rim kept, no shadow | A glass panel covered by another panel |
| `opaque` | `surface` with `glassRim`, no sheen | Reduce transparency and unsupported devices |

Text and icons on glass are always fully opaque. Glass never blurs text and is never tinted with the accent.

### Typography

System Android sans-serif, normal tracking, regular and medium weights. Proposed sizes: screen title 28 sp (Settings, Quick settings, channel name in the schedule); programme title in details card 28 sp medium; schedule row title 20 sp medium; body and settings labels 18 sp; guide channel name and cell text 14 sp; metadata 14–16 sp; toolbar labels 11 sp. Toolbar labels and guide cells are the smallest text in the app and must pass the large-font check (section 11). Section headers use title case, not all caps. Retain system font scaling and language fallback.

### Shape

Corner radii follow one concentric rule: **inner radius = outer radius − padding between them**, never below 4 dp.

| Element | Radius | Derivation |
| --- | --- | --- |
| Guide details card, grid panel, channel schedule, quick settings, Settings sidebar | 24 dp | Outer floating surfaces |
| Rows inside those panels (12 dp padding) | 12 dp | 24 − 12 |
| Guide rail | Full capsule (24 dp) | Narrow vertical capsule |
| Rail item fill (8 dp padding) | 16 dp | 24 − 8 |
| Ruler strip, player toolbar capsule | Full capsule | Single-line strips |
| Toolbar item circle (40 dp) | Circle | Chosen toolbar focus shape |
| Player programme strip | 20 dp | Shorter outer surface |
| Channel-number tile | 8 dp | Small identity block |
| Day tabs, status capsule | Full capsule | Short single-line choices |
| Full Settings content rows, buttons, text fields | 12 dp | Match rows in panels |
| Programme cells | 6 dp | Near-rectilinear because width encodes duration |
| Guide preview | 12 dp | Video, content |

Pills are reserved for the rail, ruler, toolbar, day tabs and status capsule.

### Icons

Locally authored or permissively licensed Android vector line glyphs, 1.5 dp stroke, chosen for the actual action. The concept set includes search, TV, film, record, bookmark and settings for the rail; the 14 toolbar actions; and folder, calendar, play, record, grid, display and info for Settings categories. Every icon has an accessibility label. Do not use SF Symbols or assets from other apps. Creating new glyph or banner assets requires user approval before work starts.

## 6. Material behavior on Android

### Implementation tiers

Android has no direct equivalent of Liquid Glass refraction or luminosity adaptation. Choose the tier per panel and never fake a higher tier.

| Tier | Mechanism | Applies when |
| --- | --- | --- |
| Backdrop blur | Window background blur, API 31+: `Window.setBackgroundBlurRadius`, `FLAG_BLUR_BEHIND` with `blurBehindRadius` | The panel is its own window (quick settings drawer, sheets, status capsule) and `WindowManager.isCrossWindowBlurEnabled()` is true (listen with `addCrossWindowBlurEnabledListener`). Whether video is included must be verified per device |
| Tinted glass | `glassRegularFlat` / `glassClear` plus rim, specular, sheen and shadow | All in-window panels: guide panels, rail, channel schedule, player strip and toolbar, Settings sidebar; any device without blur |
| Opaque | `opaque` | App-level Reduce transparency, contrast failure |

`RenderEffect` blurs a view's own content, not what is behind it. Do not use it on a control subtree to imitate glass. Do not capture, copy or re-render video frames (`PixelCopy`, `TextureView` bitmaps) to fake a backdrop or ambient color. Do not change the video surface type just to enable a material.

Consequences for the concepts:

- **Guide backdrop (C1).** The concept shows the preview's colors blurred behind the guide. The implementation uses the static `ambient` gradient instead, because sampling video is excluded. Tinting the gradient from a channel logo would need a palette dependency and separate approval.
- **Guide panels over a static backdrop.** In-window blur of a static drawable is technically possible with `RenderEffect` on the backdrop view itself (API 31+). It adds little because the backdrop is already soft, so the tinted tier is the default.
- **Overlays on video (C2, C3, C4).** The channel schedule and player strip/toolbar sit in the same window as the video and use tinted glass. The video shows through the tint, darkened, but not blurred. Quick settings is a dialog window and may get real blur where supported.

### Variant rules

- **Regular**: any panel with multiple lines of text, lists or fields.
- **Clear**: only the player toolbar capsule, which carries icons and short labels over video. The 35% dimming layer is always present.
- **Full-attention tasks** (full Settings, playlist setup forms, EPG import) use a dedicated screen without video rather than a translucent sheet.

### Calculated contrast

Worst-case figures, calculated from the tokens against a uniform white or black backdrop without blur. Detailed video behind tinted glass is harder to read than a uniform field, so these are minimums to verify, not proof.

| Surface | Backdrop | Primary text | Secondary text | Accent |
| --- | --- | --- | --- | --- |
| `glassRegular` (80%) | white | 7.96:1 | 4.66:1 | 4.10:1 |
| `glassRegular` (80%) | black | 16.32:1 | 9.55:1 | 8.41:1 |
| `glassClear` (55% + 35% dim) | white | 6.23:1 | 3.65:1 | 3.21:1 |
| `glassClear` (55% + 35% dim) | black | 17.53:1 | 10.26:1 | 9.04:1 |
| `surface` (opaque) | n/a | 15.22:1 | 8.91:1 | 7.85:1 |
| `lens` (94%) with `lensText` | white / black | 17.54:1 / 15.30:1 | n/a | n/a |

The 78% `glassRegular` token is close to the calculated 80% case. Recalculate it before implementation and raise opacity if secondary text falls below 4.5:1 over white. `glassRegularFlat` (88%) is more opaque and meets at least the 80% figures. Rules:

- `glassClear` carries only primary text and icons. The C3 "Unavailable" sub-label on the toolbar must therefore be `textPrimary` at regular weight, not grey.
- On regular glass, accent is used for marks, progress, the time label and short status words ("Now", "Active") at 16 sp or larger medium weight. It is not used for body text.
- "Now" on the lens (C2) is accent text on a pale accent chip; verify 4.5:1 against the lens.

## 7. Focus and remote behavior

Focus is the glass moment: unfocused items are quiet; the focused one becomes the lit lens. There is no parallax and no touchpad dependency.

| Element/state | Proposed treatment |
| --- | --- |
| Focused programme cell | Lit lens with `lensSpecular`, `lensText`, unchanged cell geometry, no scale |
| Focused row (schedule, quick settings, Settings sidebar) | Lit lens; row grows 2 dp outward; in the schedule, one extra description line appears |
| Focused toolbar item | Icon circle becomes the lit lens with a dark glyph, 1.1 scale, label turns medium weight; neighbours keep their positions |
| Focused button or text field (full Settings, sheets) | Lit lens fill for buttons; 2 dp lens edge for text fields |
| Focused day tab | Lit lens capsule |
| Selected but unfocused (rail destination, day tab, settings category after focus moves right) | `fillQuietStrong` plus selected semantics |
| Tuned channel | Blue play marker plus accessible "Playing" state, independent of focus |
| Current time | Blue line and blue time label on the ruler, independent of focus |
| Opener of the current overlay | Thin lens-colored ring on the toolbar item that opened quick settings (C4) |
| Pressed control | Lens brightens to 100% for 80 ms; toolbar items 0.96 scale; cells and rows change state without scale |
| Unavailable action | Visible "Unavailable" wording and reason, focusable for reading, never an enabled no-op |

Do not scale duration-based EPG cells. Focus becomes visible on the same frame as the key press; animation follows.

Remote contract (unchanged from the current prototype and earlier evidence, plus the routes in section 3):

- Up/Down in the guide moves channel focus without tuning.
- Left/Right moves programme focus on a shared time window; the ruler and all rows scroll together while identities stay anchored.
- At the current-window left boundary, Left opens groups; Left from groups can expose main navigation.
- Center on another current channel tunes and keeps the guide; Center on the same tuned current channel opens fullscreen.
- Future programme activation never tunes a current stream silently.
- Fullscreen with controls hidden: Left/Right switches channel within the active group, without wrapping.
- Fullscreen OK opens the programme panel; Down moves to the toolbar; Up returns to the panel; Down again restores the remembered toolbar item. Back closes the top layer before leaving fullscreen.
- Channel schedule: Up/Down moves between programmes, Left/Right switches Today/Tomorrow when the tabs are focused. Focusing or activating a programme never tunes; the overlay is information only until programme actions are separately specified.
- Quick settings: Up/Down moves rows; Center opens that row's existing option sheet; Back closes the drawer and restores the toolbar Settings item.
- Full Settings: Up/Down moves categories; Right or Center moves into the content; Back from content returns to the sidebar, and Back from the sidebar leaves Settings.
- Held Back may return to fullscreen; ordinary Back stays sufficient.
- Media Play/Pause follows actual player capability. Show seek actions only for streams that support seeking.

Store the originating control and selected item identity when opening a layer. Restore focus to that control if it still exists; otherwise choose the nearest valid item. Async logos, guide updates and player callbacks must not steal focus or rebuild the active control tree.

## 8. Screen specifications

### 8.1 TV guide (C1)

Layout as in section 4. Behavior is unchanged.

- Rail: glass capsule with Search, TV, Movies, Recordings, My list and Settings near the bottom. Only working destinations are shown. The current destination uses `fillQuietStrong`.
- Preview: solid live video.
- Details card: focused programme title, start–end and channel, progress bar with "N min left" only for a valid current programme, and up to three lines of description. Missing EPG shows "No information" without invented times.
- Ruler strip: half-hour labels in `textSecondary`; the current time label in `accent` with a dot where the time line starts.
- Grid panel: channel identity column (number in `textSecondary`, name in `textPrimary`, play marker for the tuned channel) and programme cells in `fillQuiet`. The focused channel's other cells use `fillQuietStrong`. One long "No information" cell for channels without guide data. The blue time line crosses the panel. Rows are separated by 1 dp white 6% lines in the identity column only.
- Group pane and expanded navigation extend the rail capsule to the right and push the guide panels. They do not cover the panels.
- Logos, when present, sit in a fixed slot before the name; missing logos never change row height.

### 8.2 Channel schedule overlay (C2), new

Purpose: see what is on the tuned channel later without leaving the picture.

- Opened from the fullscreen programme panel's new **Schedule** action. Video keeps playing under a 30% scrim.
- Glass panel on the left 60%: channel-number tile and channel name, then "Programme guide · this channel" in `textSecondary`.
- Day tabs "Today" and "Tomorrow", shown only for days with stored programmes. The stored guide keeps up to 48 events per channel within the next 24 hours, so Tomorrow often holds only a few hours.
- Rows: time range, title, duration. Past programmes use `textPast`. The current programme shows a "Now" chip and a progress bar. The focused row shows one line of description.
- Focus opens on the current programme. The list fades at the bottom edge and scrolls.
- Footer: "Stored guide · updated HH:MM". When there is no guide data, the panel says "No programme information for this channel." and points to Settings → EPG.
- No tuning, reminders or recordings from this overlay in this design. Back returns to the programme panel. The seven-second overlay timeout is suspended while it is open.

### 8.3 Fullscreen player (C3)

Video is the focal point until controls open. With controls hidden, nothing is drawn except the buffering indicator.

- **Programme panel** (OK): glass panel above the bottom inset with group and clock, channel tile and name, title, start–end, next programme, progress (only with real EPG), stream badges, a small down chevron, and the actions TV guide, History, Schedule and Retry (Retry only after failure). Same focus behavior as today plus the new Schedule action.
- **Toolbar** (Down): the panel collapses into the compact **programme strip** and the `glassClear` toolbar capsule appears beneath it. The strip shows channel tile, title, start–end and next, progress, and badge chips ("1080p", "50 fps", "Stereo") from actual track data. It is information only and not focusable; Up still returns to the full panel.
- Toolbar order is exactly `PlayerToolbar.entries`, with group dividers that do not change focus order: Search, Channels list, Recordings | Multiview, Picture-in-picture | Video tracks, Audio tracks, Audio offset, Closed captions | Display mode, Sleep timer | Add to Favorites, Channel options, Settings. (C3 groups Recordings with Search; this design follows C3.)
- Each item: 40 dp icon circle with label below. Sleep timer shows its remaining time as a second label line when active. Unavailable items keep a full-contrast glyph and add an "Unavailable" line.
- **Option sheets** (Video tracks, Audio tracks, Audio offset, Closed captions, Display mode, Sleep timer, Channel options): a glass sheet grows upward from the activating toolbar item or quick settings row. The toolbar or drawer behind it recedes. Back collapses the sheet into its source.
- **Buffering**: 48 dp accent ring on a 72 dp tinted-glass disc, centered.
- **Errors**: glass card in the panel position with the message and focused Retry. It never auto-hides.

Overlay auto-hide remains seven seconds (prototype policy), suspended for sheets, drawers, the schedule overlay, errors and accessibility reading.

### 8.4 Quick settings (C4), replaces the current Settings dialog

Purpose: playback adjustments for the tuned channel while it keeps playing.

- Opened from the toolbar's Settings item. Glass drawer on the right, above the toolbar. The toolbar stays visible below and recedes; its Settings item shows the opener ring.
- Title "Quick settings" and "<number> <channel> · this channel".
- Rows with icon, label and current value, drawn from real state: Video tracks, Audio tracks, Closed captions, Display mode, Sleep timer ("This session" scope), Audio offset ("Experimental"), Channel options (favorites and reset).
- Last row, after a small gap, with a chevron: **All settings** / "Playlists, EPG, appearance".
- Rows open the existing dialogs and behaviors, restyled as option sheets. No new storage and no new global preferences.
- Items from the current Settings dialog that are not playback-scoped (Playlists, Recordings, Multiview, EPG, Picture-in-picture availability) move to full Settings; PiP stays in the toolbar.

### 8.5 Full Settings (C5)

Purpose: configuration with full attention.

- Dedicated screen. On entry, the video is hidden and playback stops, following the existing behavior for leaving the player. On return, the same channel and focus are restored without tuning another channel. Whether playback resumes automatically follows the current return policy (the playlist setup flow, for example, suppresses playback until explicit action).
- Static `canvas` + `ambient` backdrop. Glass sidebar on the left with title "Settings" and categories. Content on the right uses opaque `surface` rows, not glass, so the long-form pages read calmly.
- Categories, shown only when they have working content:

| Category | Contents (existing features unless marked) |
| --- | --- |
| Playlists | Saved playlists with source type and channel count; active playlist marked by a filled radio and "Active" once; Add playlist, Rename, Reorder, Delete with confirmation. Focus + Center switches the active playlist without starting playback |
| EPG | XMLTV import (URL, saved Xtream, playlist-declared source), stored schedule browser, manual mappings, opt-in 6-hour refresh |
| Recordings | Saved recordings, foreground recording limits, playback and delete |
| Multiview | Two-stream start and its warnings |
| Playback | Shown only once a real global playback setting exists; channel-scoped options stay in Quick settings |
| Appearance | Proposed: Reduce transparency, Reduce motion, font size note. Shown only when implemented |
| About | App version and build, open-source licenses, synthetic/private data notice |

- The current separate activities (Playlists, Playlist setup, EPG, EPG browser, All playlists, Recordings/Multiview) become Settings pages or open from them, keeping their flows and fields. Forms (playlist setup, XMLTV URL) open as content pages, not sheets.
- Helper text sits under the page title (for example "Choose the active playlist. Switching does not start playback.").

### 8.6 Other screens

- **All playlists and EPG schedule browser**: static backdrop, one glass header bar with title, search field and actions, opaque rows beneath, fading edge. Video previews are solid video.
- **Search**: broad text field (`surfaceRaised`, 12 dp radius) in the glass header bar, optional labeled voice action, results scope stated (channels only). The system keyboard is not restyled.
- **Movies and My list**: later design work. Do not populate them with realistic-looking invented libraries.

## 9. Stock Android UI replacement plan

Goal: no default framework visuals remain visible in the app. This section is a plan; nothing below is implemented. Counts are from a source scan of `app/src/main/java` on 2026-10-30 and are approximate.

| Current stock element | Approx. uses | Where | Replacement | Behavior that must not change |
| --- | --- | --- | --- | --- |
| `Theme.Material.NoActionBar`, `colorAccent #42A5F5` | 1 | `res/values/styles.xml` | App theme with `canvas` window background, proposed text colors and custom widget styles | Leanback launch, landscape, PiP support |
| `AlertDialog` / `Dialog` | ~39 / ~19 | `MainActivity`, Playlist(s), EPG, All playlists, EPG browser, Advanced playback | `GlassSheet` (choice, confirmation, information) as a dialog window; the Settings dialog becomes the quick settings drawer | Back dismissal, focus restoration, initially focused button, secure flag, recreation restoring open dialogs |
| `Button` | ~59 | All screens | `GlassButton` (lens on focus); `ToolbarItem` in the toolbar | Click semantics, accessibility names, programmatic focus targets used by tests |
| `EditText` | ~10 | Playlist setup, EPG, search, rename | `GlassTextField` | IME actions, password masking, no credential logging |
| `ListView` + `ArrayAdapter` | ~9 / ~5 | Playlists, EPG, All playlists, EPG browser, option lists | `RecyclerView` rows in the row style | Stable IDs, row restoration, 200-row cap |
| `CheckBox` | ~2 | EPG options | Toggle row with On/Off value text | Saved state, explicit refresh opt-in |
| `Spinner` | ~1 | Setup source selection | Choice row opening an anchored `GlassSheet` | Selected value persistence |
| `Toast` | ~22 | All screens | `StatusCapsule` with polite accessibility announcement | Every message kept; none becomes input-blocking |
| Media3 `PlayerView` default controller | 1 | `AdvancedPlaybackActivity` | Custom transport following section 8.3 | Saved recording playback controls |
| Media3 default buffering spinner | 1 | `MainActivity` | Section 8.3 buffering indicator | Shown only during real buffering |
| Hard-coded `setBackgroundColor` / `GradientDrawable` colors | ~12+ | `FullscreenChrome`, `GuideRows`, activities | Token references and glass drawables | `FullscreenContrastChecks` updated to new tokens |
| `MenuIcon` text glyphs ("DVR", "⚙", "↔") | 1 class | `FullscreenChrome` | Vector line icons (asset creation needs approval) | Icon-to-action mapping, accessibility labels |

Not replaceable by the app: the system keyboard, system PiP window controls, system permission prompts and the launcher.

Migration risk: instrumentation suites locate dialogs, buttons and rows by structure and text. Replace components behind shared factories first, keep visible text and accessibility names stable where the route is unchanged, and update test helpers in the same change. The Settings dialog → quick settings change and the new Schedule action need new test coverage. Run the full isolated suites before calling any screen migrated.

## 10. Data and failure states

| Context | Loading | Empty | Error and recovery |
| --- | --- | --- | --- |
| Catalog | Centered wordmark splash on canvas while the saved playlist loads (see docs/prototype.md, Launch branding) | "No playlist added." / Add playlist | "Channels could not be loaded." / Retry or Settings → Playlists |
| Guide | Keep identities visible; "Loading programme information…" | Per-row "No information" | Keep last usable guide, label the failure, offer Retry |
| Channel schedule | Stable rows with "Loading…" | "No programme information for this channel." / Settings → EPG | Keep stored data; state that it may be out of date |
| Playback | Buffering indicator; keep action focus | "Select a channel to watch." / TV guide | "This channel could not be played." / Retry and TV guide |
| Quick settings | Values show "Loading…" until tracks are known | "No alternative tracks on this channel." | Keep the prior applied value and name the failed change |
| Search | "Searching channels…" | Separate "no query" from "No matching channels." | Keep query and offer Retry |
| Favorites | Stable row space | "No favorite channels yet." with Channel options instructions | Keep existing membership visible; explain save failure |
| Full Settings | "Loading settings…" without focus jumps | Category hidden when it has no working content | Keep prior value; identify the failed change |

These strings are proposed interface copy, Tiviman design choices. Connect each action before exposing it. Never show provider URLs, credentials or raw server responses. Fixtures and screenshots must be marked synthetic and must not use private provider metadata. The channel and programme names in the concepts are invented.

## 11. Accessibility and motion

- Every reachable action has native Android click behavior, an accessible name, role and current state. Icon visuals always carry labels.
- Support D-pad and hardware keyboard. Back/Escape dismisses the top layer and restores focus. No core task needs long press, voice, hover or touch.
- Announce programme title, channel, start/end and playing state without reading the full description on every key repeat. `StatusCapsule` messages use polite announcements.
- Contrast: at least 4.5:1 for ordinary text and 3:1 for focus indicators and meaningful marks. Verify over the brightest and darkest real video frames with the tier actually in use.
- Large text: test the supported font scale range including a 200% stress case. The 30 dp guide rows, 11 sp toolbar labels and 14 sp cell text are the highest risk; reflow, reduce rows, or widen the toolbar items rather than clip text.
- **Reduce transparency** (proposed app setting): every panel uses the opaque tier with the same geometry, rim and focus. Android 14 has no stable public system query for this, so the app setting is the control.
- **Reduce motion** (proposed app setting) and system animator scale 0: morphs become 100 ms crossfades; no focus scale. The lens still appears instantly.
- Proposed timings: lens move and focus scale 120 ms; panel ↔ strip/toolbar morph 200 ms; option sheet grow-from-source 220 ms; quick settings drawer 240 ms; channel schedule panel 240 ms; status capsule 160 ms. Decelerate on entry, accelerate on exit. Interruptible, never queued behind key repeats, never delaying input.
- Not allowed: idle shimmer, moving sheen or specular, light following focus across the screen, autoplaying carousels, decorative parallax.

## 12. Native implementation constraints

Retain the Kotlin/native view direction. Keep recycled guide rows with stable IDs, a shared timeline projection and one persistent player instance across guide/fullscreen transitions. Layout and focus state stay independent of player preparation.

Build the material as a small set of shared components so the tier can switch at runtime without rebuilding focused views: glass background drawable (tint, rim, specular, sheen), shadow, lens drawable, `GlassPanel`, `GlassSheet`, `GlassButton`, `GlassTextField`, `ToolbarItem`, `StatusCapsule`, `QuickSettingsDrawer`, `ChannelScheduleOverlay` and the full Settings shell. Do not allocate blur or per-cell material on EPG rows; cells are plain `fillQuiet` drawables. Only live glass casts a shadow.

Performance targets require device measurement. This document claims no frame rate, blur cost, latency, memory bound or visual parity. Shadows and multiple translucent panels over video add composition cost on low-end TV devices; if navigation stutters, prefer `glassRegularFlat` without shadow before reducing layout.

## 13. Acceptance before implementation is called finished

This document and the concepts are not executable mockups. No application build, remote traversal, screenshot comparison or new emulator capture was performed for this revision.

Validate an implementation with deterministic synthetic catalog/EPG data and local test media, using only `faisalnh.tiviman`. Do not touch other packages on the test device:

1. All remote behaviors in section 7 behave as in the current prototype, plus the new routes in section 3. Existing JVM and isolated suites pass after test-helper updates.
2. Screen-by-screen scan: no framework-default dialog, button, field, list, checkbox, spinner, toast, player controller or spinner remains (section 9), apart from listed system UI.
3. No glass panel ever stacks on another; covered panels recede.
4. Channel schedule: opens on the current programme, never tunes, shows honest empty/partial-day states, and Back restores the Schedule action.
5. Quick settings: every row opens its existing behavior and shows real values; All settings leaves video; returning restores the same channel without an unexpected tune.
6. Full Settings: every visible category has working content; playlist switching does not start playback; forms keep IME and masking behavior.
7. Exercise loading, no playlist, missing/mixed EPG, no search matches, unavailable tracks, playback failure and save failure without private provider traffic.
8. Measure contrast over the brightest and darkest synthetic video frames for each tier. Text is never blurred; `glassClear` carries no grey or accent text.
9. Check concentric radii, inset panels, the 30 dp row pitch and the toolbar at 720p, 1080p and 4K at their reported densities and at large font scales.
10. Toggle Reduce transparency and Reduce motion at runtime and set animator scale to 0. Navigation and focus stay identical.
11. On a device where `isCrossWindowBlurEnabled()` is true, record whether quick settings blur includes the video. On one where it is false, confirm the tinted tier appears without errors.
12. Measure repeated navigation with playback and material on a lower-capability TV device against the opaque tier; record measurements and device identity.
13. Compare the running app against concepts C1–C5 for layout and hierarchy, not pixel parity.

Completion means a working TV guide/player with evidence of these behaviors, not a polished still image.

## 14. Decision log

| Decision | One-line reason |
| --- | --- |
| Glass panels side by side, never stacked | User asked for more glass; stacking is what makes glass muddy and unreadable |
| Guide panels are glass over a static backdrop | Gives the guide the requested material without moving content behind text |
| Static ambient gradient instead of video colors | Sampling video frames is excluded for privacy and reliability |
| Channel schedule as an overlay on the playing channel | User feedback: the viewer should keep watching while checking the schedule |
| Quick settings over video, full Settings without video | User feedback: playback tweaks in context, configuration with full attention |
| Horizontal toolbar kept with its exact order | Chosen toolbar structure and user requirement |
| Programme strip above the toolbar | Keeps channel and progress visible while browsing tools (C3) |
| Lit lens focus with specular edge | Gives the light focus surface the glassy finish of the concepts |
| Clear variant only for the toolbar | Only element over full-motion video with short labels |
| Mandatory 35% dimming under clear glass | Live video brightness is unpredictable; calculated white-backdrop contrast needs it |
| Concentric radii | Visual continuity between panels and rows |
| Categories hidden until they have content | No dead or fake settings pages |
| Tiered material with opaque as a full design | Android cannot blur video behind in-window views; honesty over fake blur |
| Android sans-serif and own glyphs | Licensing and Android language/font-scale support |

## 15. Implementation notes (2026-10-30)

Implemented as specified, with these recorded deviations:

- Guide layout follow-up: Search, TV and Settings form one vertically centered rail group, per user feedback. The identity column is **224 dp** instead of 128 dp so channel names retain room after four-digit numbers and the tuned marker; ruler and time cursor use the same shared origin. Preview/details have matching minimum heights and an 8 dp gutter; the details viewport owns the panel background so short empty-EPG content does not collapse its surface. Timeline edges clip to a 12 dp inner radius; the ruler remains visible without EPG, using the device's local clock and half-hour labels independently of unavailable programme data. These are proposed layout corrections, Tiviman design choices.
- Guide row pitch stays **39 dp** (not the proposed 30 dp) and the timeline stays 320 dp/hour; both are tested geometry. Revisit only after viewing-distance and large-font checks.
- "Now" chip uses `#174A78` text on `#D6E9FF` (7.41:1) because `accent` is too light for text on a pale chip; the schedule progress track uses a matching neutral.
- Schedule shows only "Today" for fixture data (fixture times cannot establish midnight); real XMLTV can show Tomorrow.
- Focused schedule rows do not grow 2 dp outward.
- Quick settings uses window background blur on the drawer only (no full-screen blur-behind) so the receded toolbar stays legible.
- The toolbar capsule grows above 64 dp when labels wrap or fonts are enlarged; it uses native viewport clipping so partially visible actions remain drawn during swipes, preserves its scroll offset across focus updates, and smoothly reveals offscreen focused actions. Android fading edges and overscroll effects remain disabled because they draw opaque bars against the translucent capsule.
- Playback and Appearance Settings categories are omitted (no working content yet). Settings pages route to the existing Playlists/EPG/Recordings/Multiview activities, restyled with `GlassPage` headers.
- Remaining framework visual: the Media3 controller on recording playback in `AdvancedPlaybackActivity` (also the system keyboard, PiP controls and permission prompts).

Checked against synthetic screenshots from the `designCapture` instrumentation suite (see [development](docs/prototype.md#tests)). Those screenshots stay local and aren't checked in.

## 16. Applied brand and launch behaviour (2026-11-01)

The TV banner uses the full integrated Tiviman wordmark. Launcher icons use the standalone lowercase `t` with a blue cape, including adaptive, monochrome and legacy variants. See [brand rules](docs/brand.md) and [current logo](docs/brand/brand-update.md).

`MainActivity` shows `R.drawable.tiviman_wordmark`, a crop of the banner wordmark paths, 300 logical px wide and centered on canvas `#101216` during saved-playlist loading. Alpha pulses from 1.0 to 0.55 with ease in-out, taking 1,400 ms per half cycle. The minimum display time is 600 ms, enforced by `remainingSplashDelay` in `SplashTiming.kt` and unit-tested in `SplashTimingTest`, followed by a 200 ms fade out. Pulse and fade are skipped when animator duration scale is 0. The splash container takes focus and has the content description “Tiviman. Loading saved playlist”. Menu routing is unchanged.

`Theme.Tiviman.Launch` applies to `MainActivity` only, using `drawable/tiviman_launch.xml` for the static wordmark on canvas. `onCreate` switches to `AppTheme`. On API 31+, the system splash uses the canvas colour with a transparent icon.

Verification on 2026-11-01, Android 14 TV emulator, 1920 × 1080: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passed with 124 unit tests; cold-launch screenshots showed the centered full wordmark followed by the guide; `scripts/smoke-prototype.py` passed all nine checks; `PrototypeChecks` passed 27 on-device checks. `aapt2 dump badging` confirmed label `Tiviman`, icon `@mipmap/ic_launcher` and banner `@drawable/tv_banner`.

Reduced motion, the TalkBack announcement, Menu during the brief splash, the saved-playlist error path and launch continuity below API 31 were checked by code review only, not exercised on device. They remain in the [device testing checklist](docs/human-testing.md#install-and-launch).

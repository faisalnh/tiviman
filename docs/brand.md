# Tiviman brand plan

Status: **final assets supplied by the owner and applied (2026-11-01).** The owner's asset kit replaces the "Now" mark in section 3 with an integrated lowercase `t` wordmark and a blue cape. Current construction and usage rules are in [`brand/brand-update.md`](brand/brand-update.md), and the source kit is in [`brand/`](brand/). Where this document and `brand/brand-update.md` disagree on the logo, `brand/brand-update.md` wins. Sections 2, 4, and 5 (name, voice, color, typography) still apply. Sections 3 and 8 are kept as history.

Design Read: brand identity for an open-source Android TV IPTV player, used from the sofa with a D-pad remote, in the same dark cinematic glass language as the app, dial **ENERGY 2 / RHYTHM 1 / MOTION 1**. The brand adds no new visual language. It takes the three things the UI already repeats and turns them into a mark.

## 1. What the brand is built from

The UI's identity motif (`design.md` section 2) has three parts. The brand uses all three, and nothing else:

| UI element | What it is in the app | What it becomes in the brand |
| --- | --- | --- |
| Lit lens | The bright focus surface on the focused programme | The horizontal bar of the mark |
| Current-time line | The single blue vertical line through the guide | The stem of the mark |
| Ruler marker | The dot on the time ruler above the grid | The dot above the mark |

If the name were swapped out, the mark would still read as "the guide at this moment". That's the test it has to pass.

## 2. Name and voice

- **Name in text:** `Tiviman`. Capital T, always one word. Never `TiviMan`, `TIVIMAN`, or `Tivi Man`.
- **Wordmark:** lowercase `tiviman` (reason: the mark already carries the T shape, so a capital T next to it would repeat it).
- **Descriptor (one line, for store listings and README):** "Open-source IPTV player for Android TV, built around the programme guide."
- **Voice:** plain and specific, like a TV remote's labels. Name the feature and what it does. No buzzwords, no claims that can't be checked, no em dashes, no emoji.

## 3. Logo

> Superseded on 2026-11-01 by the owner's asset kit. See `brand/brand-update.md`. The text below is the original concept, kept as history.

### 3.1 The mark: "Now"

A letter T made from the guide's geometry: a lit programme cell crossed by the current-time line, with the ruler dot above it.

```text
              ●            ruler dot (accent)
        ╭─────┃─────╮
        │░░░░░┃░░░░░│      lit lens bar (lens white, specular top edge)
        ╰─────┃─────╯
              ┃
              ┃            current-time line (accent), forms the T stem
              ┃
```

Construction on a 24 × 24 unit grid:

| Part | Size and position | Color | Reason |
| --- | --- | --- | --- |
| Lens bar | 18 × 5 units, top at y = 6, centered, radius 1.5 | `lens` `#F5F7FA` | Same proportions as a short programme cell with the 6 dp cell radius scaled down |
| Lens specular | 0.5 unit line inside the bar's top edge, white 60% | White | The same `lensSpecular` finish the focused cell has in the app |
| Time line | 2.5 units wide, from y = 5 to y = 21, centered, square ends | `accent` `#77B8FF` | The guide's time line is a straight hairline, so the ends stay square. Starting 1 unit above the bar shows that it crosses over the bar |
| Ruler dot | 3.5 unit circle, center at y = 2 | `accent` | The current-time dot on the ruler strip. Leaves a 1.25 unit gap above the line so the dot stays separate at small sizes |
| Line over bar | The line is drawn on top of the bar | n/a | In the guide, the time line passes over programme cells |

Small-size rules:

- **Below 32 px:** drop the specular line.
- **Below 20 px:** drop the dot as well, so the bar and stem stay distinct.
- **One-color versions** (themed icons, print, notification): every part is the same color. Leave a 1-unit gap in the bar on each side of the stem so the crossing still reads.

Things the mark never does: gradients, glow, outlines, tilting, a 3D bevel, tinting the bar blue, or swapping the blue for another color. The blue has one meaning in the app ("now"), and the mark keeps it.

### 3.2 Lockups

| Lockup | Use | Construction |
| --- | --- | --- |
| Horizontal | README header, website, store graphics | Mark + `tiviman` wordmark. Mark height = wordmark cap height × 1.6. Gap between mark and wordmark = 2 × the lens bar's height |
| Mark only | Launcher icon, favicon, avatar | Mark centered on `canvas` |
| Wordmark only | Places where the mark is already shown nearby | `tiviman`, no mark |

Clear space around any lockup: at least the height of the lens bar on every side.

### 3.3 Android deliverables (applied owner kit)

The original concept above is historical. These assets use the current lowercase `t` and cape artwork, not the "Now" mark.

| Asset | Current integration | Notes |
| --- | --- | --- |
| TV banner (`tv_banner.xml`) | Full integrated wordmark on `canvas` | Base drawable is flat `#101216`; API 24+ adds the radial `#1C2027` to `#101216` ambient gradient |
| Adaptive launcher icon | Lowercase `t` and blue cape on `canvas` | API 26+ adaptive resources and legacy PNG icons are included |
| Monochrome icon layer | One-color `t` and cape with the gap preserved | API 33+ adaptive icons include this layer |
| Repository social preview | Integrated wordmark and descriptor on `canvas` | Source and previews are in `docs/brand/`. Never use private provider screenshots |

Check the icon and banner on a TV launcher. Resource integration alone does not establish their appearance on every launcher.

## 4. Color

The brand palette is the UI palette. No new colors are added for the brand, so the launcher, the banner, and the app's first screen all read as one product.

| Role | Token | Hex | Where |
| --- | --- | --- | --- |
| Ground | `canvas` | `#101216` | Icon and banner background, dark documentation |
| Raised ground | `surface` | `#1C2027` | Cards on dark brand surfaces |
| Lens | `lens` | `#F5F7FA` | Mark bar, wordmark on dark |
| Quiet text | `textSecondary` | `#B8C0CC` | Descriptor and captions on dark |
| Now Blue | `accent` | `#77B8FF` | Time line and dot in the mark, on dark grounds only |
| Now Blue Deep | new, brand use only | `#1F5FAD` | Replaces Now Blue on white or light grounds (README in light mode, print) |
| Ink | `canvas` | `#101216` | Wordmark and mark bar on light grounds |
| Ink secondary | new, brand use only | `#5A6472` | Captions on light grounds |

Measured contrast (WCAG ratios, from `.agents/skills/antislop-human/contrast-check.py`):

| Pair | Ratio | Result |
| --- | --- | --- |
| Lens `#F5F7FA` on canvas `#101216` | 17.47:1 | Passes AA, normal text |
| Quiet text `#B8C0CC` on canvas | 10.22:1 | Passes AA, normal text |
| Now Blue `#77B8FF` on canvas | 9.00:1 | Passes AA, normal text |
| Now Blue on surface `#1C2027` | 7.85:1 | Passes AA, normal text |
| Now Blue on white | 2.08:1 | **Fails**, so it's never used on light grounds |
| Now Blue Deep `#1F5FAD` on white | 6.37:1 | Passes AA, normal text |
| Ink `#101216` on white | 18.75:1 | Passes AA, normal text |
| Ink secondary `#5A6472` on white | 6.00:1 | Passes AA, normal text |

Dose rule: blue is the one accent. In any brand graphic it covers less area than the lens white, just as it does in the guide.

Dark by default (reason): the product is a TV app viewed in dim rooms over video, and the launcher icon sits on the TV home screen. A light-ground version exists only for documentation and print.

## 5. Typography

| Use | Typeface | Weight | Reason |
| --- | --- | --- | --- |
| Wordmark | Atkinson Hyperlegible Next | SemiBold, tracking −1% | Built for telling letters apart at low acuity, which fits a name read from across a room. Its distinct `i`, `l`, and `1` also help the two `i`s in the name and channel numbers. Licensed under the SIL Open Font License, which fits an open-source project |
| Brand graphics, README headings, store art | Atkinson Hyperlegible Next | Regular and Medium | One family across all brand material |
| In-app UI | System Android sans-serif (unchanged) | Regular and Medium | Keeps `design.md` section 5: language fallback, user font scaling, no bundled font weight in the APK |

The wordmark is set once, then converted to outlines in the vector asset, so the app doesn't need to bundle the font. Before converting, adjust by hand:

- Tighten the spacing in `vi` and `iv` slightly so the word holds together at banner size.
- Make the stroke weight of the wordmark match the lens bar height at the lockup ratio in 3.2.

Type rules carried over from the UI: sentence or title case, no all-caps labels, no wide letter-spacing, no monospace for decoration.

## 6. Applied repository branding

| Change | File |
| --- | --- |
| Launcher label `Fixture TV Prototype` becomes `Tiviman` (done) | `app/src/main/AndroidManifest.xml` |
| Banner, adaptive icon, monochrome icon, legacy PNG icons (done) | `app/src/main/res/drawable/`, `drawable-v24/`, `mipmap-anydpi-v26/`, `mipmap-anydpi-v33/`, `mipmap-*dpi/` |
| Manifest uses `@mipmap/ic_launcher`, `@mipmap/ic_launcher_round`, and `@string/tiviman_app_name` (done) | `app/src/main/AndroidManifest.xml` |
| Source SVGs, previews, font, build script (done) | `docs/brand/` |
| Project rule that forbids inventing a new brand (done) | `AGENTS.md` |
| References to the old label and ID (done) | `README.md`, `docs/`, `scripts/smoke-prototype.py` |

The application ID changed from `dev.fixturetv.prototype` to `faisalnh.tiviman` on 2026-11-01, and the launcher label is now `Tiviman`. The Kotlin package, `namespace`, source folders, and test runner were renamed from `dev.fixturetv` to `faisalnh.tiviman` the same day. Existing installs under the old ID cannot update in place: the new ID needs a fresh install and has separate storage. This migration rule does not describe which packages are currently installed on a test device.

## 7. Decisions

| Decision | Status |
| --- | --- |
| "Now" mark construction | Approved 2026-11-01, then superseded the same day |
| Integrated lowercase `t` wordmark with blue cape, blue `i` dots | Supplied by the owner and applied 2026-11-01 |
| Atkinson Hyperlegible Next for the wordmark | Approved 2026-11-01 |
| Package ID changes with the label | Done 2026-11-01: `faisalnh.tiviman` |
| Who draws the assets | The owner. Kit delivered 2026-11-01 |
| Banner ambient gradient | Open: the kit uses a radial `#1C2027` to `#101216`, not the `GuideBackdrop` lights (`0x661E2A36` at top right and bottom left). Reconcile if the banner should match the guide exactly |

## 8. Asset creation prompts

Image generators are unreliable with exact geometry and real typefaces. Use them to explore the look, then rebuild the final mark as a vector to the measurements in 3.1, and set the wordmark in the real font. Never add the generated wordmark lettering to the final asset.

### 8.1 Mark only (image generator)

```text
A flat vector app icon on a solid very dark blue-grey background, hex #101216.
The symbol is a geometric letter T built from three simple shapes, centered:
1. A wide horizontal rounded rectangle, off-white #F5F7FA, about 3.6 times wider than it is tall, with slightly rounded corners. A very thin brighter highlight line runs just inside its top edge.
2. A narrow vertical bar in light sky blue #77B8FF with square ends, forming the stem of the T. It starts slightly above the white bar, passes over the white bar's center, and continues down well below it. The stem is about half as thick as the white bar is tall.
3. A small solid circle in the same blue #77B8FF floating directly above the stem with a small clear gap.
The white bar is the largest shape. The blue covers less area than the white.
Strictly flat 2D, crisp edges, perfect symmetry, generous empty space around the symbol, no text.
```

Negative prompt (if the tool supports one):

```text
gradient, glow, neon, bevel, 3D, shadow, reflection, glass orb, outline stroke, tilt, perspective, texture, noise, sparkles, play button triangle, TV screen, antenna, extra shapes, letters, text, watermark
```

### 8.2 Horizontal lockup (image generator, exploration only)

```text
A flat vector logo lockup on a solid very dark blue-grey background, hex #101216.
On the left: a geometric letter T symbol made of a wide off-white #F5F7FA rounded horizontal bar, crossed at its center by a narrow sky blue #77B8FF vertical stem with square ends that extends well below the bar, plus a small blue #77B8FF circle above the stem.
On the right: the lowercase word "tiviman" in off-white #F5F7FA, in a clean humanist sans-serif with open, clearly distinct letter shapes, semibold weight, normal letter spacing.
The symbol is about 1.6 times the height of the lowercase letters' capital height, vertically centered with the word. Gap between symbol and word is about twice the white bar's height.
Strictly flat 2D, crisp edges, no other elements.
```

Use the same negative prompt as 8.1.

### 8.3 One-color version (image generator)

```text
A flat single-color vector symbol, solid white on solid black. A geometric letter T: a wide rounded horizontal bar, crossed at its center by a narrow vertical stem with square ends that extends well below the bar, and a small circle above the stem with a clear gap. Where the stem crosses the bar, a thin gap of background separates the stem from the bar on both sides, so the crossing stays visible. No highlight line, no text, perfect symmetry.
```

### 8.4 Vector build sheet (Figma, Illustrator, or Inkscape)

Artboard 24 × 24 units, origin top left. Build in this layer order, bottom to top:

| Layer | Shape | Geometry | Fill |
| --- | --- | --- | --- |
| 1. Background (icon only) | Rectangle | Full artboard | `#101216` |
| 2. Lens bar | Rounded rectangle | x 3, y 6, w 18, h 5, corner radius 1.5 | `#F5F7FA` |
| 3. Lens specular | Line or 0.5 unit rectangle | x 4.5 to 19.5, y 6.5, h 0.5 | White 60% |
| 4. Time line | Rectangle | x 10.75, y 5, w 2.5, h 16, no rounding | `#77B8FF` |
| 5. Ruler dot | Circle | Center (12, 2), diameter 3.5 | `#77B8FF` |

Small sizes: hide layer 3 below 32 px and layer 5 below 20 px. One-color: merge all to one fill, then cut a 1 unit gap from the bar on each side of the stem (bar removed from x 9.75 to 10.75 and 13.25 to 14.25).

Wordmark: type `tiviman` in Atkinson Hyperlegible Next SemiBold, tracking −1%, tighten `vi` and `iv` by hand, convert to outlines. Lockup ratios are in 3.2.

Android exports: the adaptive icon foreground keeps the mark inside the central 66 dp of a 108 dp canvas. The TV banner is 320 × 180 dp with the horizontal lockup centered. Both are listed in 3.3.

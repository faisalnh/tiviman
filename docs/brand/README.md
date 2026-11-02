# Tiviman brand asset kit

The owner's kit is applied in this repository. The integrated wordmark uses a lowercase `t` with a blue cape, no dot above the `t`, and blue dots over both `i` letters. Remaining lettering is lens white on dark backgrounds. Atkinson Hyperlegible Next SemiBold lettering is converted to paths, so the app does not need the font at runtime.

## Files

- `../../app/src/main/res/`: integrated Android resources; `build_assets.py` writes here directly.
- `source/tiviman-wordmark.svg`: transparent outlined wordmark.
- `source/tiviman-mark.svg`: standalone lowercase `t` and cape.
- `source/*-dark.svg`, `*-light.svg`, `*-monochrome.svg`: background and colour variants. Monochrome SVGs are white on transparency.
- `source/tv-banner.svg`: 320 × 180 banner source with ambient gradient.
- `source/adaptive-*.svg`: 108 × 108 layer sources.
- `source/repository-social-preview.svg`: 1280 × 640 social preview with the approved descriptor.
- `previews/`: PNG exports of logos, launcher icons, banners and social preview.
- [Android integration](android-integration.md): installed resources and checks.
- [Brand update](brand-update.md): current logo construction and usage.
- `fonts/`: variable font and SIL Open Font License, for source generation only.
- `build_assets.py`, `render_svg.cjs`: source and resource generation.

## Regenerate

From the repository root:

```sh
python3 docs/brand/build_assets.py
```

Requires Python `fontTools` and `Pillow`, plus Node.js and `sharp` available to the renderer. Generation writes source artwork, previews and Android resources; review the resulting diff before using the output.

## Integration and validation

Application ID is `faisalnh.tiviman`, with launcher label `Tiviman`. The manifest uses the generated banner, launcher and round icons, and name resource. See [brand.md](../brand.md) for name, voice, colour and typography.

The TV banner contains the full integrated wordmark. Launcher icons use only the `t` and cape. The API 24+ banner uses a radial `#1C2027` to `#101216` gradient; the base banner uses solid canvas. Whether that gradient should match `GuideBackdrop` exactly remains open.

Integration does not establish launcher/device acceptance. Check the icon and banner on a TV launcher, adaptive masks and themed icons where supported, and readability from sofa distance. No new device validation is claimed here.

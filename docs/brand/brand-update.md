# Tiviman brand update

This owner-supplied kit is applied in this repository. It supersedes the logo in [brand.md](../brand.md); that document still defines the name, voice, palette and typography.

## Selected mark

A lowercase t guardian replaces the first letter of the wordmark. Use the actual outlined lowercase t from Atkinson Hyperlegible Next SemiBold, retaining its ascender, crossbar and curved foot. A blue cape sits behind the lower left side of the t. There is no head or dot above the t. Both existing i dots are blue; their letter stems stay white.

The primary identity is the integrated wordmark, not a separate guardian followed by a second full wordmark. The standalone icon is the same lowercase t and cape, without the remaining letters. Do not add a dot to the standalone icon.

## Color and typography

- Canvas: #101216.
- Lens white: #F5F7FA.
- Blue accent: #77B8FF on dark backgrounds.
- On white, use ink #101216 and deep blue #1F5FAD, as in the original plan.
- Descriptor: #B8C0CC on dark backgrounds.
- Typeface: Atkinson Hyperlegible Next, weight 600 for wordmark, 400 for descriptor.
- Tracking: -10 font units per letter pair on a 1000-unit em, with an additional 5-unit tightening for iv and vi.
- Lettering and descriptor are outlined, with no live text or external dependencies in exported SVGs.

Geometry is defined by the SVG paths and build script, replacing the original 24 × 24 Now-mark construction. The white t remains unchanged from the actual typeface. The cape is a custom Bezier silhouette with a clear background gap between it and the t. The same gap is preserved in monochrome so the cape remains distinguishable.

## Applications

TV banner: full integrated wordmark centered with generous clear space. Launcher: standalone t and cape. Monochrome launcher: one-color version, retaining the gap. Social preview: full integrated wordmark and approved two-line descriptor. Background gradients belong to the banner only; the logo itself uses flat fills.

Keep clear space around the artwork at least equal to one letter-stem width. Never stretch, rotate, add glow or shadow, or change the accent color. Use the supplied monochrome assets for single-color applications. At small sizes retain the cape gap and avoid adding detail.

The integrated Android TV banner uses a flat #101216 canvas in the base drawable and a radial #1C2027 to #101216 ambient gradient in the API 24+ drawable. Whether the banner should match the guide backdrop lights exactly remains an open decision in [brand.md](../brand.md). Launcher icon and banner appearance still need checking on a TV launcher.

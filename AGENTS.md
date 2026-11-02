<!-- antislop:start -->
## Anti Slop

Anti Slop v3.2.24 is installed project-locally in `.agents/skills/`.
Read `.agents/skills/antislop/SKILL.md` as the core filter, then load the skills relevant to the task:

- UI / visual: `.agents/skills/antislop-ui/SKILL.md`
- Copy / text: `.agents/skills/antislop-copywriting/SKILL.md`
- Accessibility: `.agents/skills/antislop-human/SKILL.md`
- Responsive layout: `.agents/skills/antislop-layoutmobile/SKILL.md`
- Code comments: `.agents/skills/antislop-code/SKILL.md`

Follow the core's usage-mode resolution: explicit during/after selection, then the global preference, then ask if unresolved. Do not save a global preference without permission. In after mode, report numbered findings and wait for approval before fixing them.

This is a native Android TV app, not a website. Apply accessibility principles using Android semantics, D-pad navigation, system font preferences, and native UI states. HTML, CSS cursor, browser breakpoint, and Core Web Vitals requirements do not apply literally.

Existing UI direction is documented in `design.md`, `docs/core-ui-spec.md`, and `docs/ui-research.md`; the code layout and test commands are in `docs/prototype.md`. Write audit and remediation reports to the git-ignored `private/anti-slop/`, not the repository. The product brand is **Tiviman**, approved by the owner on 2026-11-01 and defined in `docs/brand.md`, with the current logo in `docs/brand/brand-update.md`. Do not invent other brand elements, and do not name other products in brand material or documentation. Do not invent visual tokens or runtime evidence.

Never inspect or publish private provider data for an aesthetic audit. On test devices, only install, clear, uninstall, start, or send input to `faisalnh.tiviman`; never touch other packages. Report static findings separately from emulator-confirmed behavior.

Installation source: https://github.com/miqdadbadjuber/anti-slop
Snapshot: `3005235d030b4793146ad9afe8d01045efd90e1d` (v3.2.24).
License: `.agents/ANTI-SLOP-LICENSE`.
Updates: `npx antislop-ai --update` replaces installed snapshots and may also update global installations; review its scope before running. Start a new agent session after updates.
<!-- antislop:end -->

# Android brand integration

The owner's brand resources are integrated in `app/src/main/res/`. Application ID is `faisalnh.tiviman`; the name resource `tiviman_app_name` is `Tiviman`.

The application manifest uses:

```xml
android:label="@string/tiviman_app_name"
android:icon="@mipmap/ic_launcher"
android:roundIcon="@mipmap/ic_launcher_round"
android:banner="@drawable/tv_banner"
```

These are attributes of the existing application, not a complete manifest. Preserve the remaining settings, permissions, activities and Leanback launcher intent filters.

## Resource behaviour

- `drawable/tv_banner.xml`: 320 × 180 dp VectorDrawable, full integrated wordmark on solid canvas `#101216`.
- `drawable-v24/tv_banner.xml`: same banner with radial `#1C2027` to `#101216` ambient gradient, API 24+.
- `drawable/ic_launcher_foreground.xml`: transparent 108 dp foreground, lowercase `t` with blue cape.
- `drawable/ic_launcher_monochrome.xml`: white `t` and cape with a visible negative-space gap.
- `mipmap-anydpi-v26/`: adaptive launcher and round icon declarations, API 26+.
- `mipmap-anydpi-v33/`: matching adaptive declarations with monochrome layers, API 33+.
- `mipmap-mdpi` through `mipmap-xxxhdpi`: 48, 72, 96, 144 and 192 px legacy icons, including round variants.
- `values/tiviman_colors.xml`, `values/tiviman_strings.xml`: canvas colour and launcher name.

The adaptive foreground's visible bounds are x=34.439..73.561 and y=30..78 on the 108-unit artboard. Its bounding-box corners fit within the radius-33 circle centred at (54,54). The standalone icon is simpler than the wordmark and has no blue dot. Actual launcher masks still need device checks.

## Regenerate and build

From the repository root, with Python `fontTools` and `Pillow`, Node.js and `sharp` installed:

```sh
python3 docs/brand/build_assets.py
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The generator writes Android resources directly to `app/src/main/res/`, as well as source artwork and previews. Review generated changes before building. Keep the project's established SDK configuration; monochrome declarations require compile SDK 33+.

## Launcher acceptance

Check the full wordmark banner on a TV launcher, legacy and adaptive icon masks, and monochrome icons on a supporting Android 13+ launcher. Check from sofa distance. These checks are required follow-up, not device validation performed by this documentation update.

A package-ID change requires a fresh install. The current ID is already `faisalnh.tiviman`; do not choose another ID as part of asset regeneration.

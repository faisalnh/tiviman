# Versioning and releases

## Version numbers

Tiviman uses [Semantic Versioning](https://semver.org/). While the version starts with `0.`, any release may change behaviour or saved-data formats. `1.0.0` is the first release that promises upgrade compatibility.

Both numbers live in `app/build.gradle.kts`, and nowhere else:

| Field | Rule | Example |
| --- | --- | --- |
| `versionName` | SemVer, matches the git tag without the `v` | `0.2.0`, `0.2.0-beta.1` |
| `versionCode` | Add 1 for every published APK, including betas. Never reuse or decrease it | `1`, `2`, `3` |

Android refuses to install an APK whose `versionCode` is lower than the installed one, so a mistake here strands users on the old build.

Tags:

- `v0.2.0`: stable release.
- `v0.2.0-beta.1`, `v0.2.0-rc.1`: published as a GitHub pre-release.

## Release signing

Every release must be signed with the same key. A different key means users have to uninstall, which deletes their saved playlists. Losing the key has the same effect for every future release.

Create the key once, on your own machine:

```sh
keytool -genkeypair -v -keystore tiviman-release.jks -alias tiviman \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep `tiviman-release.jks` and both passwords in a password manager, with an offline backup. Never commit the file; `.gitignore` already excludes `*.jks` and `*.keystore`.

Add four repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `TIVIMAN_KEYSTORE_BASE64` | Output of `base64 -i tiviman-release.jks` |
| `TIVIMAN_KEYSTORE_PASSWORD` | Keystore password |
| `TIVIMAN_KEY_ALIAS` | `tiviman` |
| `TIVIMAN_KEY_PASSWORD` | Key password |

The Gradle build reads the same names as environment variables (`TIVIMAN_KEYSTORE_PATH` instead of the base64 value). Without them, `assembleRelease` produces an unsigned APK.

Every official APK is signed with this certificate:

```text
SHA-256: 944a9531153d6b5a20ce2f8429e76cc24ee53296c3b917192c465953147c92bc
```

Check a downloaded APK with:

```sh
apksigner verify --print-certs tiviman-0.1.0.apk
```

The `Signer #1 certificate SHA-256 digest` line must match. If it doesn't, the APK didn't come from this project.

## Cutting a release

1. Update `versionName` and `versionCode` in `app/build.gradle.kts`.
2. Move the `Unreleased` notes in `CHANGELOG.md` under the new version, and add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters at most).
3. Commit, then tag and push:

   ```sh
   git tag -a v0.2.0 -m "Tiviman 0.2.0"
   git push origin main v0.2.0
   ```

4. The `Release` workflow checks that the tag matches `versionName`, runs the unit tests, builds and signs the APK, verifies the signature, and creates the GitHub release with:
   - `tiviman-<version>.apk`
   - `tiviman-<version>.apk.sha256`
   - a build provenance attestation
5. Edit the generated release notes if needed, then test the APK on a TV by installing it over the previous release.

The workflow refuses to publish when signing secrets are missing, so an unsigned APK can't reach the releases page.

## Checking a downloaded APK

```sh
shasum -a 256 -c tiviman-0.2.0.apk.sha256
gh attestation verify tiviman-0.2.0.apk --repo faisalnh/tiviman
```

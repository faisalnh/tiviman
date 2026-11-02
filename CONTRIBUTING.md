# Contributing to Tiviman

Thanks for helping. Tiviman is maintained by one person, so small, focused changes get reviewed fastest.

## Reporting bugs

Use the bug report form in [Issues](https://github.com/faisalnh/tiviman/issues/new/choose). Include your TV model, Android version, Tiviman version, source type (M3U, Xtream, or Stalker), and the remote keys you pressed.

Keep provider data out of public issues. Never post playlist or portal URLs, usernames, passwords, MAC addresses, tokens, raw logcat output, or UI dumps. Screenshots of setup screens and channel lists can contain the same data, so crop or blur them first.

## Before opening a pull request

For anything larger than a bug fix, open an issue first so we can agree on the approach.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

CI runs the same command on every pull request. Lint warnings are allowed for now; lint errors fail the build.

Pull requests should:

- Keep to one change. Unrelated formatting or renames make review harder.
- Add or update a JVM test in `app/src/test/` when the logic can be tested without a device.
- Work with a D-pad alone. Every new control needs a visible focus state and a route back with Back.
- Follow [design.md](design.md) and the [core UI specification](docs/core-ui-spec.md). Don't add new colours, fonts, or brand elements; [brand.md](docs/brand.md) defines them.
- Update the relevant document in `docs/` when behaviour or limits change.

## Testing on a device

Use an emulator or a device you don't rely on. The instrumentation checks (`PrototypeChecks`) use synthetic data and refuse to run when a real playlist is saved; don't bypass that guard. Only install, clear, or uninstall `faisalnh.tiviman`, never other packages.

`scripts/capture-ui.py` captures the running emulator UI and can send remote keys:

```sh
python3 scripts/capture-ui.py example DPAD_LEFT
```

Captures go to `private/`, which git ignores. Raw captures can contain private playlist metadata, so don't commit them or attach them to issues.

## AI coding agents

If you use an AI agent, point it at [AGENTS.md](AGENTS.md). You are responsible for every line it writes, and the privacy rules above apply to anything it reads from a device.

## Scope

Tiviman plays sources the user already has the right to use. Contributions that bundle channel lists, bypass provider authentication, or clone another device's identity won't be accepted.

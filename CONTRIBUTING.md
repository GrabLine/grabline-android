# Contributing to GrabLine for Android

Bug reports, documentation fixes, translations and focused code changes are
welcome. Read [docs/SPEC.md](docs/SPEC.md) first: it is the source of truth for
features, screens, error messages and security rules.

## Before you start

- Report a reproducible problem with the GitHub bug form. Include the app
  version, device, Android version, the GrabLine version on your PC, steps and
  the actual result.
- Discuss large features or architecture changes in an issue first. Phase 6
  (cloud servers) is on hold until there is demand.
- Report exploitable findings privately through [SECURITY.md](SECURITY.md).
- Keep reports and reviews respectful, specific and focused on the work.

## Development setup

You need Git, **JDK 17** and the Android SDK (compileSdk and targetSdk 36,
minSdk 26). Android Studio is optional.

```sh
git clone https://github.com/GrabLine/grabline-android.git
cd grabline-android
./gradlew assembleDebug
```

Use `.\gradlew.bat` on Windows PowerShell. Put `sdk.dir` in `local.properties`;
never commit that file.

## Checks

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

CI runs these three tasks. Emulator tests do not run in CI. Run them locally on
a Pixel-class emulator when your change touches the UI, services or storage:

```sh
./gradlew connectedDebugAndroidTest
python scripts/e2e/run_e2e.py
```

`run_e2e.py` drives the app against the desktop listener in loopback test mode
(the emulator reaches the host at `10.0.2.2`). It needs a desktop checkout. The
protocol and its JSON fixtures live in the desktop repo under `protocol/`;
`./gradlew syncProtocolFixtures` copies them into
`app/src/test/resources/protocol/`. Change the protocol in the desktop repo
first, then sync.

For a bug fix, write a failing test first, then fix it. Port a desktop
regression test to the phone when the same bug can happen here.

## Conventions

- Kotlin and Jetpack Compose. Package root `dev.grabline.app`, with feature
  packages `remote`, `data`, `ui.<screen>`, `engine` and `notify`.
- Strings go only in `res/values/strings.xml`. Every control has a content
  description, tap targets are at least 48 dp, and meaning never depends on
  colour alone.
- Error messages use the plain words in the spec's "Error states and edge cases".
- QR scanning uses ZXing, not ML Kit, so the app stays buildable for F-Droid.
  Do not add analytics, ads or Google Play services.
- The phone never listens on a port and never sends cookies to the PC.
- Never log pairing codes, keys or signatures.
- Changes to pairing, auth, TLS, keys or certificate pinning need a careful
  security review, plus tests.

## Branches and pull requests

- `main` is the only long-lived branch. Start a topic branch from `main` and
  open pull requests against it. Do not force-push `main`.
- Write commit messages in the imperative, with a short summary line such as
  "Validate the pairing code before storing the pin", and a body for the why.
- Keep a pull request focused. Say what changed and what you actually ran. Use
  the PR template.
- Do not commit `local.properties`, keystores, build output, secrets or logs.

The [website](https://grabline.github.io/) and the
[desktop app](https://github.com/GrabLine/GrabLine) have their own repositories.

Contributions are distributed under the repository's [AGPL-3.0 license](LICENSE).

# GrabLine for Android

One Android app that downloads on the phone and remotely controls GrabLine on a paired PC. Kotlin + Jetpack Compose. AGPL-3.0. No accounts, ads, analytics or Google Play services.

## Where things live

- `docs/SPEC.md` - snapshot of the product & QA spec (live doc: https://claude.ai/artifact/H6Jmk4WYMAnRxdvuiMEVWJ). Source of truth for features, screens, settings, errors, security rules and QA IDs.
- `docs/PROTOCOL.md` - phone <-> PC protocol (pairing, auth, every message, errors, versioning). Canonical copy and JSON fixtures live in the desktop repo at `protocol/`; `./gradlew syncProtocolFixtures` copies them into `app/src/test/resources/protocol/`.
- `docs/BUILD_PLAN.md` - every task with model, dependencies, acceptance and status. Resume from the first unfinished task.
- Desktop repo: GrabLine/GrabLine, worked on in branch `feature/phone-remote` (local worktree, see CLAUDE.local.md).

## Decisions (do not reopen)

- applicationId `dev.grabline.app`; minSdk 26; compileSdk/targetSdk 36.
- ZXing (`zxing-android-embedded`) for QR, never ML Kit (F-Droid). OkHttp WebSocket with certificate pinning. Room for jobs and the outbox. Android Keystore for the device key. Phone video: `youtubedl-android` (yt-dlp + FFmpeg). Torrents: `libtorrent4j`.
- No mDNS: the QR code carries every local PC address; the phone remembers the last one that worked.
- The phone never listens on a port. It never sends cookies or browser headers to the PC.
- Desktop: listener in `app/remote/` (Qt TCP sockets on its own thread + Python `ssl` TLS; Qt TLS can't serve the P-256 key on Windows), off by default, private and link-local binds only, loopback test mode for the emulator (10.0.2.2). Phone links enter the `handoffs` table with source `phone` and take an unattended add path (never a modal dialog; default quality `best`). PROTOCOL_VERSION 6.
- Phase 6 (cloud servers) waits for demand; do not start it.

## Model division

| Role | Model | Agent file | Does |
| --- | --- | --- | --- |
| Lead | opus | - | Plan, connection/state management, unattended add path, outbox/sync, integration, checks every diff |
| security-builder | fable | `.claude/agents/security-builder.md` | Protocol/security design; pairing, auth, TLS, key code on both sides; listener access checks |
| reviewer | fable | `.claude/agents/reviewer.md` | Read-only; PASS or numbered must-fix list; every security change and every phase gate |
| builder | sonnet | `.claude/agents/builder.md` | Gradle, Compose screens, ViewModels, Room, notifications, engine parts, tests, docs |
| runner | haiku | `.claude/agents/runner.md` | Builds, tests, lint; returns failures only; mechanical edits |

One task per helper, report in 15 lines or fewer. A task that fails twice moves up one tier.

## Conventions

- Package root `dev.grabline.app`; feature packages: `remote` (protocol, connection, pairing), `data` (Room), `ui.<screen>`, `engine` (phone downloads), `notify`.
- Strings only in `res/values/strings.xml`; every control has a content description; tap targets >= 48 dp; no colour-only meaning.
- Plain-words error messages exactly as in SPEC.md "Error states and edge cases".
- Tests: JUnit unit tests for logic and protocol fixtures; Compose UI tests and instrumented tests on the `grabline_pixel` emulator.

## Hard rules

- Commit after each finished task on `main` with a clear message; the build and affected unit tests must pass first. Local commits only.
- Anything touching pairing, auth, TLS, keys or the listener's access checks needs a reviewer PASS before it counts as done.
- Never commit CLAUDE.local.md, local.properties, keystores or secrets. Never log codes, keys or signatures.
- Keep BUILD_PLAN.md status current; before context runs long, write the exact next step there and commit.

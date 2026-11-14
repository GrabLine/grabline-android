# Build plan - GrabLine for Android

Status: todo / doing / review / done. Repo: **D** = desktop worktree `GrabLine-phone-remote` (branch `feature/phone-remote`), **A** = this repo (`main`). Model: fable (security-builder / reviewer), opus (lead), sonnet (builder), haiku (runner). Security rows (pairing, auth, TLS, keys, listener access checks) need a reviewer PASS before `done`.

**Next step:** P1-D01 and P1-A01 (setup finished; PROTOCOL.md + fixtures by fable in progress as P0-02).

## Phase 0 - Setup

| ID | Task | Repo | Model | Depends | Files | Acceptance | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| P0-01 | Spec snapshot | A | opus | - | docs/SPEC.md | Rev 24 snapshot with both diagrams transcribed | done |
| P0-02 | Protocol doc + fixtures | A+D | fable | P0-01 | docs/PROTOCOL.md, D:protocol/** | Every message, error, pairing, auth, versioning, security rule; fixture per message + invalid cases + manifest | doing |
| P0-03 | CLAUDE.md, CLAUDE.local.md, agents, plan | A+D | opus | - | CLAUDE*.md, .claude/agents/*, this file | Committed (local files ignored) | done |
| P0-04 | Repos: git init A (main), D branch feature/phone-remote | A+D | opus | - | .gitignore, LICENSE | Both repos clean after first commit | done |

## Phase 1 - PC remote (gate: QA-01-17, QA-41-44 pass; security review of the listener)

### Desktop

| ID | Task | Repo | Model | Depends | Files | Acceptance | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| P1-D01 | Dependencies + CI "no listen port" check | D | sonnet | P0-04 | pyproject.toml, scripts/check_no_listen.py, .github/workflows/ci.yml, app/tests/test_no_listen.py | `cryptography` and `segno` explicit deps; script fails on listen/bind/QTcpServer/QWebSocketServer/QUdpSocket/socketserver/http.server/asyncio start_server outside `app/remote/`; passes on the tree; CI step added; test covers both outcomes | done |
| P1-D02 | Handoffs `options` column + `phone` source | D | sonnet | P0-04 | app/db/database.py, app/tests/test_db.py | Migration via `_HANDOFFS_MIGRATIONS` (`options TEXT NOT NULL DEFAULT '{}'`); `add_handoff(..., options=)`; `Handoff.options` dict; old DB upgrades; tests | done |
| P1-D03 | PC certificate + fingerprint | D | fable | P1-D01 | app/remote/certs.py, app/tests/test_remote_certs.py | Self-signed cert/key created once in data dir (key file not world-readable where the OS allows), reloaded after restart, fingerprint per PROTOCOL.md, regenerate on demand; QA-07 basis | todo |
| P1-D04 | Pairing sessions + device store + auth verify | D | fable | P1-D02, P1-D03 | app/remote/pairing.py, app/remote/devices.py, tests | Codes single-use, 2-min expiry, lock after 5 wrong (QA-02, QA-03); Deny stores nothing (QA-04); devices persisted (pub key, name, created, last seen); revoke; ECDSA transcript verify; constant-time compares | todo |
| P1-D05 | Listener: TLS WebSocket server, binds, framing, pre-auth state, rate limits, revocation | D | fable | P1-D03, P1-D04 | app/remote/listener.py, app/remote/addresses.py, tests | Off by default; private + link-local binds only, loopback only in test mode (QA-11); 1 MB cap, connection survives (QA-09); unauth requests rejected with no detail (QA-05); per-device rate limit (QA-10); revoke closes live connections, next request fails < 1 s (QA-06); listener stops after the last unpair | todo |
| P1-D06 | Message handlers | D | opus (+fable review) | P1-D05, P0-02 | app/remote/handlers.py, app/remote/service.py, tests | ping/status/recent/focus/links reuse `handle_message`; download -> handoff source `phone`, options {quality default best, queueId, folderId, requestId}, cookies/headers ignored, requestId dedup (QA-14, QA-15); jobs (paged), control by id (remove keeps files), global, queues (ids only; folders never paths - QA-08), analyze; every fixture in protocol/fixtures passes (contract test) | todo |
| P1-D07 | Unattended add path | D | opus | P1-D02 | app/ui/main_window.py, app/core/manager.py, app/tests/test_remote_add.py | Phone handoffs never open a dialog (quality panel, "Download it again?", low disk); quality from options (default best); queue/folder from validated ids; magnet/.torrent routed to torrent engine without dialog; failures become job errors/events; test with dialogs monkeypatched to fail | done |
| P1-D08 | Live events | D | opus | P1-D06 | app/remote/events.py, tests | job.added/updated/finished/failed/removed + global; progress throttled <= 1/s per job; reflects PC-side changes within 1 s (QA-16, QA-13) | todo |
| P1-D09 | Settings "Phone" page + QR + approve prompt | D | sonnet (+opus integration) | P1-D05 | app/ui/settings_dialog.py (or settings_view.py), app/ui/phone_pairing.py, app/remote/qr.py | Pair a phone shows segno QR (2-min countdown, expiry message), Allow/Deny prompt names the device, device list with remove (confirm), listener on/off, port; loopback test mode in advanced; lockout warning shown (QA-03) | todo |
| P1-D10 | PROTOCOL_VERSION 6 + fixture contract tests | D | sonnet | P0-02 | app/native_host/host.py, app/tests/test_protocol_fixtures.py | Version 6; extension tests still pass; every valid fixture round-trips through handlers; invalid fixtures produce `expectError` | todo |
| P1-D11 | Security test suite (scripted client) | D | fable | P1-D06 | app/tests/test_remote_security.py | Scripted real-TLS client covers QA-01-11 at protocol level: pair/expire/lockout/deny, unpaired download, revoke-while-connected, wrong cert, path tricks, 2 MB message, 100 req/s flood, bind addresses | todo |
| P1-D12 | Docs: security model, SECURITY, PRIVACY, README | D | sonnet | P1-D05 | docs/security-model.md, SECURITY.md, PRIVACY.md, README.md | Records the one listener and every rule; README's "no listen port" claim narrowed to app/remote | todo |
| P1-D13 | Packaging keeps Qt TLS plugin | D | opus | P1-D05 | packaging/* | PyInstaller output contains Qt `tls` plugin (qopensslbackend) and QtWebSockets; checked at gate | todo |

### Android

| ID | Task | Repo | Model | Depends | Files | Acceptance | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| P1-A01 | Gradle project skeleton | A | sonnet | P0-04 | settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradle wrapper 9.1.0, app/build.gradle.kts, app/src/main/** | `assembleDebug` + `testDebugUnitTest` pass; applicationId dev.grabline.app; minSdk 26, target/compile 36; Compose + M3; no GMS deps; `syncProtocolFixtures` task | done |
| P1-A02 | Theme, fonts, design tokens | A | sonnet | P1-A01 | ui/theme/** , res/font | Dark tokens from spec, light from desktop design.py, IBM Plex Sans/Mono, system/light/dark + wallpaper option; contrast >= 4.5:1 checked in a unit test (QA-44) | todo |
| P1-A03 | Protocol models + codec + fixture tests | A | sonnet | P0-02, P1-A01 | remote/protocol/** , test | kotlinx.serialization models for every message; unknown fields ignored, wrong types rejected; all fixtures parse/serialize; >1 MB refused | todo |
| P1-A04 | Device key, pinned TLS, QR parse, pairing + auth client | A | fable | P1-A03 | remote/security/** , remote/pairing/** , tests | Keystore P-256 non-exportable key; trust manager accepts only pinned cert (QA-07 message); QR + manual code parse; pair flow states (approved, declined QA-04, expired QA-02, locked QA-03); auth transcript matches desktop fixtures | todo |
| P1-A05 | Connection manager | A | opus | P1-A04 | remote/connection/** , tests | Tries last good address then the others (QA-12 within 10 s); reconnect with backoff; request/reply correlation and timeouts; event stream; version gate "Update GrabLine on your PC" (QA-17); revoked -> "pair again" (QA-06); identity changed (QA-07) | todo |
| P1-A06 | Room database | A | sonnet | P1-A01 | data/** , tests | Tables: pcs, pc_jobs (cache), outbox, settings via DataStore; migrations tested | todo |
| P1-A07 | Outbox + sync | A | opus | P1-A05, P1-A06 | remote/outbox/** , tests | Links queue while PC offline, "Will send when your PC is back" + count; delivered exactly once with requestId (QA-14); double share within 1 s -> one job (QA-15) | todo |
| P1-A08 | Ways in: share target, paste, clipboard offer, magnet/.torrent | A | sonnet | P1-A01 | AndroidManifest.xml, ui/entry/** | Appears as "GrabLine" in share menu for text/URLs; paste box; offer copied link on open (setting); magnet: and .torrent intents open Add sheet | todo |
| P1-A09 | Add sheet | A | sonnet | P1-A05, P1-A08 | ui/add/** | Link + kind; device choice (PCs; Phone from Phase 2); quality presets Best..360p, MP3, M4A, FLAC; queue/folder from `queues`; analysing progress; state kept on rotation and process death (QA-43) | todo |
| P1-A10 | Downloads list + details | A | sonnet | P1-A05, P1-A06 | ui/downloads/** , ui/details/** | One list with device badge + text; filter chips; live progress; offline PC rows greyed "Last seen"; empty state; swipe pause/cancel; long-press multi-select; pause/resume/cancel/retry/remove with 5 s Undo (QA-16); pull to refresh; progress a11y "45 percent, 2 minutes left" | todo |
| P1-A11 | Devices, QR scanner, PC screen | A | sonnet | P1-A04, P1-A05 | ui/devices/** , ui/pc/** | Pair via ZXing scanner, camera-denied manual code; pairing states; PC screen: queues, speed now, pause all/resume all, speed cap, default queue, remove pairing with confirm; offline tips | todo |
| P1-A12 | Welcome, Settings (Phase 1 groups), About | A | sonnet | P1-A02 | ui/welcome/** , ui/settings/** , ui/about/** | First-run cards, notification permission with reason, Skip; settings per spec (phase-1 groups functional, later groups present but disabled with "Coming in a later update"); About with licences | todo |
| P1-A13 | Notifications | A | sonnet | P1-A05 | notify/** | All five channels created; PC activity notifications while connected (finished/failed) | todo |
| P1-A14 | Accessibility + RTL + large font pass | A | sonnet | P1-A09-A12 | ui/** , androidTest | Compose UI tests: every control labelled (QA-41), 200% font no clipping (QA-42), light/dark (QA-44), RTL layout mirrors | todo |
| P1-A15 | Emulator + instrumented tests | A | haiku/sonnet | P1-A14 | androidTest/** | AVD grabline_pixel created (android-35, off C:); connectedDebugAndroidTest passes | todo |
| P1-A16 | End-to-end: emulator <-> desktop (loopback test mode) | A+D | opus | all P1 | scripts/e2e/** | Pair, send link, live progress, control actions, revoke - against a real desktop listener at 10.0.2.2 | todo |
| P1-G | Phase 1 gate | A+D | runner + fable | all P1 | - | Full suites, review PASS, QA table updated, tag phase-1-gate | todo |

## Phase 2 - Phone downloads (outline; expand at phase start)

Engine port (rules 1-10 from SPEC), segment planner + work stealing, Room jobs/segments with checkpoints, user-initiated data transfer jobs (API 34+) / dataSync FGS (older), Wi-Fi only / charging only / speed cap, MediaStore + SAF storage, name uniqueness, free-space check, temp name + rename, notifications with actions, phone/PC choice + remembered default + rules, paste many links, tablet two-pane. Misbehaving MockWebServer suite (port of media_server.py). QA-18-32, QA-45.

## Phase 3 - Video on the phone (outline)

youtubedl-android (yt-dlp, FFmpeg) as an on-demand module if size demands, quality picker + presets, HLS/DASH, in-app yt-dlp update with approval, 13 translations + RTL. QA-33-36, QA-46.

## Phase 4 - Between devices (outline)

Send jobs phone<->PC, files/fetch with Range resume (Transfers screen), PC outbox "for my phone", several PCs, dashboard figures, full format list + trim, start later, SHA-256 check, widget + Quick Settings tile. QA-38-40.

## Phase 5 - Torrents on the phone (outline)

libtorrent4j: magnets, .torrent, DHT, sequential, seeding rules, Wi-Fi only by default. QA-37.

## Phase 6 - Cloud servers

Not started: waits for user demand (ask before starting).

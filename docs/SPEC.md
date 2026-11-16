<!-- Snapshot of Claude Docs "GrabLine for Android: Product & QA Spec" (https://claude.ai/artifact/H6Jmk4WYMAnRxdvuiMEVWJ), doc revision 24, taken 2026-11-14. The live doc is the source of truth; refresh this snapshot when it changes. -->

# GrabLine for Android: Product & QA Spec

Nov 14, 2026 · @zain

One Android app that downloads on the phone itself and remotely controls GrabLine on your PC: share any link, choose Phone or PC, and manage both from one list. Build it in six phases, starting with the PC remote, because the desktop already does the hard work.

## Goals and principles

The app has one job: get any link downloaded on the right device, with no friction. It keeps the promises the desktop app already makes.

**What it must do**

- Take a link from anywhere on the phone (share menu, paste, magnet link) in two taps or fewer.
- Download on the phone itself, or send the job to a paired PC, chosen per download.
- Show phone and PC downloads in one list, with live progress, and control both.
- Move work between devices: send a phone job to the PC, or copy a finished PC file to the phone.

**Principles carried over from the desktop**

- No accounts, no ads, no analytics, no servers run by GrabLine.
- No network listener on the phone. The phone always connects out to the PC.
- The PC listener is off until the user pairs a phone, and only answers paired devices.
- Resume must never produce a corrupt file: every partial download is validated before it is continued.
- Never overwrite an existing file; save beside it as `name (1).ext`.
- Advisory security only: warn, never quarantine.

**Non-goals**

- No DRM removal, no login bypass. The same refusals as the desktop.
- No browser extension on the phone. Chrome on Android has no extensions, and Firefox on Android has no Native Messaging. The share menu replaces it.
- No cloud relay for remote access away from home. Users who want that can use a private network such as Tailscale.
- No editing of desktop settings beyond the quick controls listed in this spec.

## Architecture

*Diagram (transcribed): "Every link goes to this phone or to your PC through one paired connection."*

- Android app: Ways in (share menu, paste or copied link, magnet and torrent links) → Add sheet (Download on: Phone or PC; quality preset; folder or queue) → either **Phone engine** (segments + resume with OkHttp; yt-dlp + FFmpeg for video; torrents with libtorrent4j; saves to phone storage) or **PC remote** (TLS with pinned certificate; device key in Keystore; live progress over WebSocket).
- PC remote → "Wi-Fi, paired, encrypted" → **Remote listener (new, the only new PC part)**: off until a phone is paired; local network only; the extension's message format.
- Remote listener → **Handoffs table** (shared with the browser extension, which uses the Native Messaging pipe and opens no ports; read by the running app) → **Download manager** (resolver, engines, queues; files saved on the PC).

The phone never listens for connections; it always connects out to the PC. Links sent to the PC land in the same handoffs table the browser extension uses, so the desktop's resolver, quality logic and engines work unchanged.

## Feature list

Thirty-six features across five areas. "Must" is the minimum for that phase to ship; change the Priority column as plans firm up.

| Area | Feature | Runs on | Priority | Phase |
| --- | --- | --- | --- | --- |
| Adding | Appears in the Android share menu as "GrabLine" | Both | Must | 1 |
| Adding | Paste box, plus an offer to send a copied link when the app opens | Both | Must | 1 |
| Adding | Offline outbox: links wait and are delivered when the PC is back | PC | Must | 1 |
| Adding | Opens `magnet:` links and `.torrent` files | Both | Should | 1 (PC), 5 (phone) |
| Adding | Video quality presets: Best, 2160p to 360p, MP3, M4A, FLAC | Both | Must | 1 (PC), 3 (phone) |
| Adding | Full format list: codec, resolution, subtitles, clip trim | Both | Could | 4 |
| Adding | Paste many links at once | Both | Should | 2 |
| Adding | Start later at a chosen time | Both | Should | 4 |
| Adding | Choose Phone or PC per download, with a remembered default and rules | Both | Must | 2 |
| Phone engine | Segmented download over up to 16 connections | Phone | Must | 2 |
| Phone engine | Resume after network loss, app kill or reboot | Phone | Must | 2 |
| Phone engine | Servers without range support or with unknown size | Phone | Must | 2 |
| Phone engine | Video and audio through yt-dlp and FFmpeg | Phone | Must | 3 |
| Phone engine | HLS and DASH streams | Phone | Should | 3 |
| Phone engine | Wi-Fi only, charging only and speed cap | Phone | Must | 2 |
| Phone engine | Save to Downloads or a chosen folder; open and share the file | Phone | Must | 2 |
| Phone engine | SFTP, FTP, WebDAV and S3 | Phone | Could | 6 |
| Phone engine | SHA-256 check against a pasted checksum | Phone | Could | 4 |
| Phone engine | Torrents through libtorrent4j | Phone | Should | 5 |
| PC remote | Pair a PC by scanning a QR code | PC | Must | 1 |
| PC remote | Live list of PC downloads: progress, speed, time left | PC | Must | 1 |
| PC remote | Pause, resume, cancel, retry and remove | PC | Must | 1 |
| PC remote | Pause all and a quick speed cap | PC | Should | 1 |
| PC remote | Choose the PC queue and destination folder | PC | Should | 1 |
| PC remote | Phone notification when a PC download finishes or fails | PC | Should | 1 |
| PC remote | Several paired PCs | PC | Should | 4 |
| PC remote | PC dashboard figures: speed now, today's total | PC | Could | 4 |
| Cross-device | Send a job from the phone to the PC, or back | Both | Should | 4 |
| Cross-device | Copy a finished PC file to the phone over Wi-Fi | Both | Should | 4 |
| Cross-device | PC queues a link "for my phone", fetched when the phone connects | Both | Could | 4 |
| App | One list for both devices, with a device badge and filters | Both | Must | 1 |
| App | Download notifications with pause and cancel buttons | Phone | Must | 2 |
| App | Light and dark theme | Both | Must | 1 |
| App | Accessible with TalkBack, large text and keyboard | Both | Must | 1 |
| App | Home-screen widget and Quick Settings tile | Both | Could | 4 |
| App | English plus the desktop's 13 translations, including right-to-left | Both | Could | 3 |

## Phone download engine

Port the desktop engine's rules, not its code: the desktop is Python and Qt, the phone is Kotlin. Every rule below already exists in `app/core/downloader.py` and was tested in the November 2026 audit.

| Part | How it works on Android | Library |
| --- | --- | --- |
| HTTP downloads | Probe with `Range: bytes=0-0`, split into segments, idle connections steal the tail of slow ones | OkHttp |
| Job database | Jobs, segments and progress, checkpointed every few hundred milliseconds | Room (SQLite) |
| Video and audio | yt-dlp with bundled Python and FFmpeg; the same quality presets as the desktop | `youtubedl-android` (used by the Seal app) |
| HLS and DASH | Fetch segments natively, then remux locally with FFmpeg | `youtubedl-android` FFmpeg module |
| Torrents | Magnets, DHT, sequential download for playback | `libtorrent4j` (same libtorrent engine as the desktop) |
| Cloud protocols | SFTP, FTP, WebDAV, S3 | sshj (with full BouncyCastle), Apache Commons Net, OkHttp. S3 uses a small hand-written AWS SigV4 signer over OkHttp instead of the MinIO client, to keep the APK small |
| Background work | User-initiated data transfer jobs on Android 14+, a `dataSync` foreground service on older versions | JobScheduler, WorkManager |
| Storage | `MediaStore.Downloads` by default, any folder the user picks through the system picker | Storage Access Framework |

**Rules the engine must keep** (each one has a desktop test to port):

1. Ask for `Accept-Encoding: identity`; refuse a body the server compresses anyway.
2. Check every `206` reply's `Content-Range` against the range requested before writing a byte.
3. Send `If-Range` with a strong ETag or Last-Modified; restart, never splice, if the file changed.
4. Treat `416` on a known-size file as "the file changed": retry with a fresh probe, not a permanent failure.
5. Treat `429` and `503` (and `403` after data has flowed) as server pushback: halve the connections and remember the number for that job.
6. Retry network errors with jittered backoff; give up only after a fixed budget with no progress.
7. Never let two unfinished jobs share a filename in one folder; pick `name (1).ext` at add time.
8. Check free space before starting, and treat "No space left" as permanent, not retryable.
9. Write the file under a temporary name; rename into place only after the size check passes.
10. Cancelling a paused job removes all its scratch files, including yt-dlp's `.part` files.

**Android limits to design around**

- Downloads that outlive the screen need a visible notification. Without one, Android stops the work.
- Android 15 caps `dataSync` foreground services at 6 hours in 24. Long jobs on Android 14+ must use user-initiated data transfer jobs instead.
- Doze and maker battery savers (Xiaomi, Samsung, OnePlus) pause network access. The app must survive being paused and resume cleanly, and should offer a one-tap link to the battery-optimisation setting.
- Bundling Python, yt-dlp and FFmpeg adds roughly 30 to 70 MB. Ship it in Phase 3, and consider a separate download of the video module on first use.
- yt-dlp breaks when sites change. The app needs an in-app yt-dlp update that does not require a new APK.

## Pairing and the phone-to-PC protocol

The phone speaks the same JSON messages the browser extension already sends through `app/native_host/host.py`, over an encrypted local-network connection. One message format, three clients: extension, phone, and any future one.

**Pairing (once per phone)**

1. On the PC: Settings, Phone, Pair a phone. The PC turns on its listener and shows a QR code that expires in 2 minutes.
2. The QR code holds the PC's local addresses, port, its TLS certificate fingerprint (SHA-256) and a one-time pairing code.
3. The phone scans it, connects, checks the certificate matches the fingerprint, and sends the code plus its own device name.
4. The PC shows "Allow Pixel 8 to control GrabLine?". Only after Allow does it issue a long-lived device key, stored in the phone's Android Keystore.
5. Both sides list the pairing. Either can remove it; the PC's removal takes effect immediately.

**Finding the PC again**: the phone first tries the last address that worked, then looks for `_grabline._tcp` on the local network (mDNS), then shows "PC not found" with a retry. A PC on a different network is reachable through a private network tool such as Tailscale, with no change to the app.

**Transport**: HTTPS with the pinned certificate for requests, plus one long-lived WebSocket for live progress events. Every request carries the device key. Messages are capped at 1 MB, matching `app/native_host/protocol.py`. Every reply carries `protocolVersion`; the phone shows "Update GrabLine on your PC" when the PC is too old.

| Message | Direction | Purpose | Exists today |
| --- | --- | --- | --- |
| `ping` | Phone to PC | Version, PC name, is the app running | Yes |
| `download` | Phone to PC | Hand off a link, optional quality preset, queue and folder | Yes (extended) |
| `status` | Phone to PC | Progress for given links | Yes |
| `recent` | Phone to PC | Newest jobs | Yes |
| `focus` | Phone to PC | Bring the PC window forward | Yes |
| `links` | Phone to PC | Hand off many links for the picker | Yes |
| `jobs` | Phone to PC | Full job list, paged | New |
| `control` | Phone to PC | Pause, resume, cancel, retry or remove one job by id | New |
| `global` | Phone to PC | Pause all, resume all, set or clear the speed cap | New |
| `queues` | Phone to PC | Queue names and the PC's favourite folders | New |
| `analyze` | Phone to PC | Quality options for a video link, resolved by the PC | New |
| `files` / `fetch` | Phone to PC | List finished files; stream one to the phone with Range support | New |
| `outbox` | Phone to PC | Links the PC queued for this phone | New |
| `events` | PC to phone, over the WebSocket | Job added, progress, finished, failed | New |

**Rules for new messages**

- A folder from the phone must be one of the PC's own download folders or favourites, chosen from the `queues` reply. Never a free-typed path.
- `remove` keeps finished files on disk, exactly as the desktop's Remove does.
- `fetch` serves only files of finished jobs, by job id. Never a path.
- The phone never sends cookies or browser headers. Signed-in downloads stay with the desktop extension.

## Security and privacy

The PC listener is the only new way into a user's computer, so it gets the strictest rules in the project. Each rule is a QA test case below.

| Threat | Rule |
| --- | --- |
| Someone on the same Wi-Fi sends commands | Listener off until pairing; every request needs a device key; unknown keys get no detail in the reply |
| A fake PC impersonates yours | The phone pins the PC's certificate fingerprint from the QR code and refuses any other |
| Pairing code guessed or reused | Codes are single-use, expire in 2 minutes, and lock pairing after 5 wrong attempts |
| A stolen phone keeps access | Removing the phone on the PC revokes its key at once; the phone's key lives in Android Keystore |
| The phone writes anywhere on the PC | Folders come only from the PC's own list; job ids, never paths, in `control` and `fetch` |
| Oversized or malformed messages | 1 MB cap, strict JSON schema, unknown fields ignored, rate limit per device |
| The listener exposed to the internet | Bound to private and link-local addresses only; never opened in the router (no UPnP for it) |
| Downloaded files are dangerous | The same advisory checks as the desktop: warn, never quarantine, never auto-open |

**Privacy**

- No analytics, crash reporting or ads SDKs. Crash logs stay on the phone until the user chooses to share one.
- No internet permission is used for anything but downloads, yt-dlp updates the user approves, and reaching the paired PC.
- Cookies and signed-in sessions are never read from phone apps or browsers.
- PRIVACY.md gets a phone section listing exactly what the app contacts, kept in step with the code (the website once drifted from it).

## Changes needed in the GrabLine desktop app

All of Phase 1's desktop work is one new module plus a Settings page. Downloads still flow through the existing `handoffs` table, so analysis, quality choice and engine routing need no changes.

| Change | Where | Notes |
| --- | --- | --- |
| Remote listener: TLS, device keys, rate limits | New `app/remote/` | Off by default; starts only while a phone is paired |
| Message handlers | `app/remote/`, sharing code with `app/native_host/host.py` | One handler per message type; the existing ones reused |
| Pairing and device list | `app/remote/` + `settings` table | QR code, one-time code, approve prompt, revoke |
| mDNS announcement `_grabline._tcp` | `app/remote/` | Needs a new dependency (`zeroconf`); see open questions |
| Settings page "Phone" | `app/ui/settings_dialog.py` | Pair, list devices, revoke, port, listener on or off |
| Job control by id for remote use | `app/core/manager.py` | Pause, resume, cancel, retry and remove already exist; expose them |
| Finished-file streaming with Range | `app/remote/` | Serves finished jobs' files by job id only |
| Outbox for "send to my phone" | New table + main window context menu | Rows deleted once the phone takes them |
| Security model update | `docs/security-model.md`, `SECURITY.md`, `PRIVACY.md` | Records the one listener and its rules |
| CI rule "no listen port" | CI check | Narrowed to allow only `app/remote/`, which must stay off by default |
| Protocol version bump | `PROTOCOL_VERSION` in `host.py` | From 5 to 6; old extensions keep working |

The desktop's existing tests for handoffs, credential scoping and the native host stay as they are; the remote module adds its own (see QA test cases).

## UI: screens

Three tabs at the bottom (Downloads, Devices, Settings) and one Add sheet that opens from anywhere: the + button, the share menu, a pasted link, or a `magnet:` link.

| Screen | What it shows | Main actions | Empty, loading and error states |
| --- | --- | --- | --- |
| Welcome (first run) | Three short cards: download here, control your PC, no accounts | Allow notifications, pick the default folder, Pair a PC or Skip | Notifications denied: a note on what you will miss, with a Settings link |
| Downloads (home) | One list of phone and PC jobs. Each row: name, device badge, progress bar, speed, time left, status | Filter chips: All, Phone, each PC, Active, Done, Failed. Tap opens Details; swipe for pause or cancel; long-press to select many | Empty: "Share a link to GrabLine, or tap +". PC offline: its rows greyed with "Last seen 5 min ago" |
| Add sheet | The link, what kind it is (file, video, torrent, page), size if known | Download on: Phone or a PC (segmented choice). Quality chips for video. Folder or queue. Start now or later | Analysing: a progress line, never a frozen sheet. Not downloadable: the reason in plain words, plus "Open in browser" |
| Quality picker | Presets first (Best, 1080p, 720p, MP3...), "More formats" below: resolution, codec, size, subtitles, trim | Pick one; the size estimate updates | Analysis failed: the site's reason and a Retry |
| Download details | Progress, speed graph, connections in use, size, source, destination, device | Pause, resume, cancel, retry, open, share, copy link, send to the other device | Failed: the error in plain words, what to try, and a Retry |
| Devices | Paired PCs: name, online or offline, GrabLine version, active jobs | Pair a PC (QR scanner), open a PC | No PCs: why pairing helps and a Pair button. Camera denied: enter the pairing code by hand |
| PC screen | That PC's queues, speed now, today's total | Pause all, resume all, speed cap, choose default queue, remove pairing | Offline: "Your PC is off or on another network" with tips |
| Transfers | Files being copied from a PC to the phone | Cancel, open when done | None: hidden from navigation |
| Settings | Grouped options (see Settings) | Change, reset | - |
| About | Version, yt-dlp version, licences, source link, support link | Update yt-dlp, view licences | Update failed: the reason and Retry |

**Interaction rules**

- Any action that takes more than 300 ms shows progress. Nothing ever looks frozen.
- Cancel and remove act at once, with a 5-second Undo; the partial file is deleted only when the Undo closes. Finished files always stay.
- Unpairing a PC asks first, because it cannot be undone from the phone.
- Pull to refresh on Downloads and Devices; live updates otherwise.

## UI design rules

The app should feel like the same product as the desktop app and the website: same colours, same type, same calm tone.

**Look**

- Material 3 components, themed with GrabLine's own colours rather than the phone's wallpaper colours (offer wallpaper colours as an option).
- Dark theme from the website: background `#07090e`, panels `#121821`, lines `#1e2736`, text `#e8eef8`, secondary text `#a4b0c4`, accent `#2563eb`.
- Light theme from the desktop's tokens in `app/ui/design.py`. Follow the system setting by default.
- Type: IBM Plex Sans for text, IBM Plex Mono for sizes, speeds and versions (the fonts the website already ships).
- Device badges: a phone icon or the PC's name, always with text, never colour alone.
- Progress bars: determinate when the size is known, indeterminate with bytes counted when it is not.

**Layout**

- Phones first; tablets and foldables get a two-pane layout (list left, details right) above 600 dp width.
- Edge-to-edge drawing with correct insets on Android 15 and later.
- Every tap target at least 48 by 48 dp.

**Accessibility** (all required for release)

- Text contrast at least 4.5 to 1; large text 3 to 1.
- Every control has a TalkBack label; progress is announced as "45 percent, 2 minutes left", not as a raw number every second.
- Works with font size at 200% and display size at its largest, with no clipped text.
- Full keyboard and switch-access navigation.
- Reduced-motion setting turns off animations.

**Languages**

- English plus the desktop's 13 translations: Arabic, Chinese, French, German, Hindi, Italian, Japanese, Korean, Portuguese, Russian, Spanish, Turkish and Urdu.
- Arabic and Urdu mirror the whole layout, right to left.
- Strings come from one source shared with the desktop where the wording is the same, so a translation is done once.
- Sizes, speeds and dates are formatted for the user's locale.

## Notifications, widget and quick settings tile

Notifications are split into channels so users can silence one kind without losing the others.

| Channel | When it appears | Buttons | Default |
| --- | --- | --- | --- |
| Active downloads | While phone downloads run; one grouped notification with a summary line | Pause all, Cancel (per job when expanded) | On, silent |
| Finished | A phone download completes | Open, Share | On, sound |
| Failed | A phone download fails for good | Retry, Details | On, sound |
| PC activity | A PC download finishes or fails, while the phone is connected to that PC | Open app | On, silent |
| Transfers | A file is copying from the PC | Cancel | On, silent |

- Progress updates at most once a second, so the notification never stutters or drains battery.
- Android 13 and later ask for notification permission on first run, with a one-line reason first.
- PC activity appears only while the app holds a live connection. There is no push service, because that would need a GrabLine server.

**Home-screen widget** (Phase 4): the 3 newest active downloads across all devices, with progress, plus Paste link and Pause all buttons. Updates at most every 30 seconds unless the app is open.

**Quick Settings tile** (Phase 4): one tap opens a small Add sheet with the copied link already filled in (Android 10 and later only let an app on screen read the clipboard, so the tile cannot send it silently); a long press opens the app. The tile shows the number of active downloads.

## Settings

Defaults are chosen so a new user never has to open Settings.

| Group | Setting | Options | Default |
| --- | --- | --- | --- |
| Where to download | Default device | Ask every time, This phone, a named PC | Ask every time |
| Where to download | Rules | By type: videos, music, torrents, other files, each to Phone or a PC | None |
| Phone downloads | Save folder | Downloads, or any folder picked in the system picker | Downloads/GrabLine |
| Phone downloads | Connections per file | 1 to 16 | 8 |
| Phone downloads | Downloads at once | 1 to 5 | 3 |
| Phone downloads | Speed limit | Off, or a value in KB/s or MB/s | Off |
| Phone downloads | Only on Wi-Fi | On, Off | On |
| Phone downloads | Only while charging | On, Off | Off |
| Phone downloads | Auto-retry failed downloads | Off, 3 times, 10 times, forever | 3 times |
| Video | Default quality | Ask, Best, 1080p, 720p, audio only (MP3) | Ask |
| Video | Audio format | MP3, M4A, FLAC | MP3 |
| Video | Update yt-dlp | Automatic with approval, manual | Ask before updating |
| Torrents | Seed after download | Off, until ratio 1.0, for 1 hour | Off |
| Torrents | Torrents on mobile data | On, Off | Off |
| PCs | Paired PCs | List, Pair new, Remove | None |
| PCs | Notify me about PC downloads | On, Off | On |
| Appearance | Theme | System, Light, Dark | System |
| Appearance | Colours | GrabLine, Wallpaper | GrabLine |
| Appearance | Language | System, or any of the 14 | System |
| Privacy | Offer to send copied links | On, Off | On |
| Privacy | Keep download history | Forever, 30 days, 7 days | Forever |
| Advanced | Battery optimisation | Shortcut to the system setting, with an explanation | - |
| Advanced | Export or import settings | File | - |
| Advanced | Share a crash log | Pick a log, share it with any app | - |

## Error states and edge cases

Every failure tells the user what happened and what to do next, in plain words, never an error code alone.

| Situation | What the app does | What the user sees |
| --- | --- | --- |
| Wi-Fi drops mid-download | Pauses, waits for the network, resumes from the last checkpoint | "Waiting for network" on the row |
| Wi-Fi only is on and the phone is on mobile data | Holds the job | "Waiting for Wi-Fi" with a "Download now anyway" button |
| App killed by the system or the phone reboots | Resumes unfinished jobs on next start, or on boot if allowed | Jobs continue; nothing restarts from zero |
| Server has no range support | One connection; a restart after an interruption | "This server can't resume; it will restart if interrupted" |
| File changed on the server mid-download | Restarts cleanly rather than mixing versions | "The file changed on the server, so it started again" |
| Link expired or needs sign-in (403, 401) | Stops after a short retry | "The site refused this download. Open it in your browser and try again from there" |
| Not enough space | Checks before starting; stops, never retries | "Needs 2.1 GB, 900 MB free" with a link to storage settings |
| Same filename already exists | Saves as `name (1).ext` | The real name in the row |
| Page link with no downloadable media | Says so, offers the browser | "This is a web page, not a file" |
| DRM service (Netflix, Spotify) | Refuses up front | "Netflix protects its media with DRM. GrabLine can't download it" |
| PC offline when sending | Keeps the link in the outbox | "Will send when your PC is back" with a count |
| PC's GrabLine too old | Blocks the new features only | "Update GrabLine on your PC to use this" |
| PC removed this phone | Clears the pairing | "This PC no longer accepts this phone. Pair again to reconnect" |
| Certificate mismatch (PC reinstalled, or impersonation) | Refuses to connect | "Your PC's identity changed. If you reinstalled GrabLine, pair again" |
| yt-dlp out of date for a site | Offers an update | "This site changed. Update the video engine?" |
| Battery saver pauses the app | Resumes when allowed | A one-time tip linking to battery settings |
| Download folder removed or permission revoked | Pauses affected jobs | "Can't write to that folder. Choose another" |

## QA strategy

Test the engine against a local server that misbehaves on purpose, the way the desktop's `app/tests/media_server.py` does. Most bugs in a download manager only appear when the network or the server does something unusual.

| Layer | What it covers | Tools | When it runs |
| --- | --- | --- | --- |
| Unit | Segment planning, Content-Range checks, name uniqueness, retry rules, protocol parsing | JUnit, Kotlin tests | Every commit |
| Engine integration | Real downloads from a local server: no ranges, redirects, mid-body drops, unknown size, compressed-anyway, 416, 429 and 403 pushback, slow links | OkHttp MockWebServer, a port of `media_server.py` | Every commit |
| Protocol contract | The same JSON request and reply fixtures pass on both sides | Shared fixture folder; pytest on the desktop, JUnit on the phone | Every commit, both repositories |
| UI | Every screen and its empty, loading and error states; TalkBack labels | Compose UI tests, Espresso | Every commit |
| Screenshot | Light, dark, RTL, 200% font, tablet layout | Paparazzi or Roborazzi | Every pull request |
| Device end-to-end | Pairing, share-to-PC, phone downloads, kill and resume, real Wi-Fi | Firebase Test Lab or local devices, plus a real desktop | Before each release |
| Fault injection | Airplane mode, Wi-Fi to mobile switch, process death, reboot, Doze, full storage | adb scripts, `cmd deviceidle force-idle` | Before each release |
| Security | Unpaired device, revoked key, wrong certificate, path tricks, oversized messages, flooding | Scripted client against the desktop listener | Every commit to `app/remote/` |
| Soak | 12 hours of mixed phone and PC downloads | Scripted run on 2 devices | Before each release |

**Performance budgets** (proposed; measure on a mid-range 2023 phone)

| Measure | Budget |
| --- | --- |
| Cold start to the Downloads list | Under 1.5 s |
| Share menu tap to Add sheet visible | Under 500 ms |
| Throughput on the same Wi-Fi as the desktop | Within 10% of Chrome downloading the same file on the same phone and network |
| Memory while running 3 downloads at 8 connections | Under 150 MB |
| Battery while idle with a PC paired | No measurable drain: no background polling when the app is closed |
| APK size, phases 1 and 2 | Under 15 MB |
| APK size with the video module | Under 90 MB, or a separate on-demand download |

**Bug triage**: severity 1 is data loss or a corrupt file and blocks release; severity 2 is a broken main flow; severity 3 is everything else. Every fixed severity 1 or 2 bug gets a regression test, as on the desktop.

## QA test cases

Forty-six scenarios to run before a release that includes their phase. Download checks compare SHA-256 against the source file; a matching size alone is never a pass.

| ID | Area | Scenario | Expected | Phase | Result |
| --- | --- | --- | --- | --- | --- |
| QA-01 | Pairing | Scan the QR code, approve on the PC | Both sides list the pairing; the phone shows the PC online | 1 | Not run |
| QA-02 | Pairing | Scan a QR code older than 2 minutes | "Code expired"; nothing paired | 1 | Not run |
| QA-03 | Pairing | Send 5 wrong pairing codes | Pairing locks; the PC shows a warning; a new QR code is needed | 1 | Not run |
| QA-04 | Pairing | Press Deny on the PC prompt | Phone shows "PC declined"; no key stored | 1 | Not run |
| QA-05 | Security | An unpaired client sends `download` | Rejected; nothing queued; no job details in the reply | 1 | Not run |
| QA-06 | Security | Remove the phone on the PC while connected | Next request fails within 1 s; phone asks to pair again | 1 | Not run |
| QA-07 | Security | Reinstall the desktop (new certificate) | Phone refuses to connect and shows the identity-changed message | 1 | Not run |
| QA-08 | Security | A modified client sends a folder of `../../Windows` or an absolute path | Rejected; nothing written outside the PC's own folders | 1 | Not run |
| QA-09 | Security | Send a 2 MB message | Rejected; listener keeps running | 1 | Not run |
| QA-10 | Security | A paired device sends 100 requests a second | Rate-limited; the desktop window stays responsive | 1 | Not run |
| QA-11 | Security | Probe the listener from outside the local network; remove the last phone | Not reachable from outside; listener turns off after the last unpairing | 1 | Not run |
| QA-12 | Remote | The PC's address changes (new DHCP lease) | Phone finds it again within 10 s | 1 | Not run |
| QA-13 | Remote | Share a YouTube link from the YouTube app to the PC, Best quality | Job appears on the PC; phone shows its progress within 2 s | 1 | Not run |
| QA-14 | Remote | Share while the PC is off, then turn it on | Delivered exactly once when the PC is back | 1 | Not run |
| QA-15 | Remote | Share the same link twice within a second | One job | 1 | Not run |
| QA-16 | Remote | Pause, resume, cancel, retry and remove from the phone, then from the PC | The other side reflects each change within 1 s | 1 | Not run |
| QA-17 | Remote | Phone app against a desktop with protocol 5 | "Update GrabLine on your PC"; no crash | 1 | Not run |
| QA-18 | Phone engine | Download a 1 GB file over 8 connections | Checksum matches | 2 | Not run |
| QA-19 | Phone engine | Airplane mode at 40% for 2 minutes | Resumes from the checkpoint; checksum matches | 2 | Not run |
| QA-20 | Phone engine | Force-stop the app at 60%, reopen | Resumes; checksum matches | 2 | Not run |
| QA-21 | Phone engine | Reboot the phone mid-download | Resumes after reboot; checksum matches | 2 | Not run |
| QA-22 | Phone engine | Server without range support, interrupted once | Restarts from zero with the notice; checksum matches | 2 | Not run |
| QA-23 | Phone engine | Server replaces the file mid-download (new ETag) | Restarts; final file matches the new version | 2 | Not run |
| QA-24 | Phone engine | Server compresses despite `identity` | Fails with a clear message; no corrupt file saved | 2 | Not run |
| QA-25 | Phone engine | Two different links with the same filename at once | Two files, each matching its source | 2 | Not run |
| QA-26 | Phone engine | A file with that name already exists | Saved as `name (1).ext`; the original untouched | 2 | Not run |
| QA-27 | Phone engine | Not enough space before starting, and the disk filling mid-download | Refused with sizes; mid-download stops once, no retry loop | 2 | Not run |
| QA-28 | Phone engine | Wi-Fi only on; switch to mobile data and back | Pauses on mobile data; resumes on Wi-Fi | 2 | Not run |
| QA-29 | Phone engine | Force Doze during a download | Resumes after the idle window; checksum matches | 2 | Not run |
| QA-30 | Phone engine | A 7-hour download on Android 15 | Still running after 6 hours | 2 | Not run |
| QA-31 | Phone engine | Cancel a paused download | Partial and scratch files removed | 2 | Not run |
| QA-32 | Phone engine | Turn off notification permission | Downloads still run; the app explains what is missing | 2 | Not run |
| QA-33 | Video | 1080p video on the phone | Plays, has audio, duration matches the source | 3 | Not run |
| QA-34 | Video | Audio only as MP3 | Plays; title and artist tags present | 3 | Not run |
| QA-35 | Video | An HLS stream | Plays from start to end | 3 | Not run |
| QA-36 | Video | Update yt-dlp inside the app | New version shown; video downloads still work | 3 | Not run |
| QA-37 | Torrents | A magnet link on the phone; then on mobile data with torrents limited to Wi-Fi | Completes with correct files; waits on mobile data | 5 | Not run |
| QA-38 | Cross-device | Send a phone download to the PC | Phone job stops and its partial file is removed; the PC completes it | 4 | Not run |
| QA-39 | Cross-device | Copy a 2 GB finished file from the PC, dropping Wi-Fi halfway | Resumes; checksum matches | 4 | Not run |
| QA-40 | Cross-device | A modified client asks `fetch` for an unfinished job or a path | Rejected | 4 | Not run |
| QA-41 | Accessibility | TalkBack through every screen | Every control labelled; progress announced as percent and time left | 1 | Not run |
| QA-42 | Accessibility | Font at 200%, largest display size | No clipped or overlapping text | 1 | Not run |
| QA-43 | UI | Rotate, and kill the process, with the Add sheet open | Link and choices kept | 1 | Not run |
| QA-44 | UI | Switch light and dark while the app is open | No unreadable text | 1 | Not run |
| QA-45 | UI | Tablet and foldable: fold, unfold, rotate | Two-pane layout; selection kept | 2 | Not run |
| QA-46 | Languages | Arabic | Whole layout mirrored; sizes and dates in Arabic format | 3 | Not run |

## QA device matrix and release checklist

Support Android 8.0 (API 26) and later, which covers nearly every phone still in use (proposed; confirm against current Android distribution figures before the first release). Test on real devices where battery savers differ, because emulators do not kill background work the way makers' builds do.

| Device type | Android version | Why it is in the matrix |
| --- | --- | --- |
| Google Pixel | Newest release | Newest platform rules first; reference behaviour |
| Google Pixel | 15 | `dataSync` 6-hour cap; edge-to-edge drawing |
| Samsung Galaxy (One UI) | 14 | Most common maker; aggressive "Deep sleeping apps" |
| Xiaomi or Redmi (HyperOS) | 13 or 14 | Strictest battery killer; autostart permission |
| OnePlus or Oppo (OxygenOS, ColorOS) | 13 | Background limits differ from Pixel |
| Low-end phone, 3 GB RAM | 10 or 11 | Memory and speed budgets; process death |
| Android 8.0 emulator | 8.0 | Oldest supported version |
| Tablet | 14 | Two-pane layout |
| Foldable (Galaxy Z Fold or Pixel Fold) | 14 or 15 | Layout changes while running |

**Desktop side**: each release is tested against the newest desktop on Windows, macOS and Linux, plus the previous desktop release, so a phone update never breaks an older PC.

**Release checklist**

- [ ] All unit, integration, contract, UI and screenshot tests pass on CI
- [ ] Every QA test case for the included phases marked Pass, or Blocked with a reason
- [ ] Device matrix run complete; any maker-specific failure triaged
- [ ] 12-hour soak finished with no crash, no corrupt file and no stuck job
- [ ] Performance budgets measured and recorded
- [ ] No severity 1 bugs open; severity 2 bugs listed in the release notes
- [ ] yt-dlp version bundled is current
- [ ] Desktop and phone protocol versions documented as compatible
- [ ] PRIVACY.md and SECURITY.md updated for anything new the app contacts or listens on
- [ ] Translations complete for all 14 languages, or untranslated strings fall back to English
- [ ] APK signed with the release key; SHA-256 checksums published beside it
- [ ] F-Droid metadata and the GitHub release notes written
- [ ] Website download section updated for Android

## Roadmap

*Diagram (transcribed): six phases, each shippable on its own; a phase ships only when its gate is met.*

| Phase | Scope | Release gate |
| --- | --- | --- |
| 1 · PC remote | Pairing, share to PC, live list, pause and cancel; desktop listener and its Settings page | QA-01–17 and QA-41–44 pass; security review of the listener |
| 2 · Phone downloads | Segmented downloads, resume, notifications; choose Phone or PC for every download | QA-18–32 and QA-45 pass; soak and device matrix clean |
| 3 · Video on the phone | yt-dlp and FFmpeg, quality picker, HLS streams; in-app yt-dlp updates, all 14 languages | QA-33–36 and QA-46 pass; app size budget met |
| 4 · Between devices | Move jobs between devices, copy PC files to the phone; several PCs, widget, Quick Settings tile | QA-38–40 pass; transfers resume after a drop |
| 5 · Torrents on the phone | libtorrent4j: magnets, DHT, seeding rules; Wi-Fi only by default | QA-37 passes; battery check on 3 makers |
| 6 · Cloud servers | SFTP, FTP, WebDAV and S3 on the phone; only if users ask for it | Desktop cloud tests ported; secrets held in Keystore |

Each phase is useful on its own and ships only when its gate is met. Phases 1 to 4 are a few weeks each for one developer; torrents and cloud take longer, and Phase 6 waits for real demand.

## Risks, distribution and open questions

| Risk | Impact | What reduces it |
| --- | --- | --- |
| Google Play rejects or removes the app for downloading YouTube videos | No Play listing | Ship on F-Droid and GitHub first; treat Play as optional, perhaps a build without the video module |
| Sites change and yt-dlp breaks | Video downloads fail until updated | In-app yt-dlp updates without a new APK |
| Makers' battery savers kill downloads | Downloads stall on Xiaomi, Samsung, OnePlus | Proper background-job APIs, the battery-settings tip, and the device matrix |
| The PC listener weakens the desktop's "no ports" promise | Trust and security | Off by default, local network only, paired keys, a security review before release |
| Video module makes the app large | Fewer installs on low-storage phones | Ship it as an optional download on first use |
| Two engines (Python desktop, Kotlin phone) drift apart | Bugs fixed on one side stay on the other | Shared protocol fixtures; port each desktop regression test to the phone |
| Scope: this is several months of work for one person | Nothing ships | Ship Phase 1 alone first; each phase is useful on its own |

**Distribution and licence**

- F-Droid and GitHub releases from the start; Google Play optional.
- The app is AGPL 3.0, like the desktop, with its source public.
- Release APKs signed with one key kept offline, with SHA-256 checksums published, as the desktop does.
- Separate repository (for example `GrabLine-Android`); the protocol fixtures live in the desktop repository and are copied in by CI.

**Open questions**

- [ ] Desktop listener: Python's standard library plus `ssl`, or a small dependency for WebSockets?
- [ ] Is a new desktop dependency (`zeroconf`) acceptable for finding the PC, or should the phone rely on the last address plus manual entry?
- [ ] Minimum Android version: 8.0, or 10 to simplify storage and background handling?
- [ ] Should Phase 3 ship the video module inside the APK or as an on-demand download?
- [ ] Should the phone app list on Google Play at all, given the YouTube policy risk?
- [ ] Who signs the release APK, and where does the key live?

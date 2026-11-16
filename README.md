# GrabLine for Android

**Download on your phone. Control GrabLine on your PC.**

Free and open source. No accounts, ads or analytics.

[![CI][ci-badge]][ci]
[![License: AGPL-3.0][license-badge]][license]

[Website][website] · [Desktop app][desktop] · [Spec](docs/SPEC.md) · [Protocol](docs/PROTOCOL.md) · [Report a bug][issues]

Share a link to GrabLine from any app, or paste it. Choose **Phone** or **PC**.
Phone and PC downloads appear in one list, and you can control both.

## Status

Phases 1 to 5 are built: PC remote, phone downloads, video, moving files
between devices, and torrents. The app is **not released yet**, and no APK is
published. Real-device testing, native-speaker translation review and release
signing come first. Cloud servers (phase 6) will wait for demand. See the
[build plan](docs/BUILD_PLAN.md) for details.

## Features

| Area | What you can do |
| --- | --- |
| Add a link | Use the share menu, paste, or open a magnet link. Two taps or fewer. |
| PC remote | See the PC queue, add links, pause, resume, cancel and retry. |
| Phone downloads | Download files directly. Resume is checked before it continues. Existing files are never overwritten. |
| Video and audio | Pick a quality through yt-dlp and FFmpeg. yt-dlp updates inside the app. |
| Between devices | Send a phone job to the PC, or copy a finished PC file to the phone with a SHA-256 check. |
| Torrents | Magnets and `.torrent` files, file selection, seeding rules, Wi-Fi only on metered networks. |
| Android extras | Home screen widget and Quick Settings tile. |

## Privacy

There are no accounts, ads, analytics or GrabLine servers. The phone never
listens on a port. It connects out to the PCs you paired, and to the servers
you chose to download from. It never reads cookies or sessions from other apps.
Read the [privacy policy](PRIVACY.md).

## How pairing works

1. On the PC, open **Settings, Phone** in GrabLine. It shows a QR code.
2. Scan it with the app. The code carries the PC's addresses, a one-time pairing
   code and the fingerprint of the PC's certificate.
3. The phone checks that fingerprint on every connection and refuses any other
   certificate. Later logins use a device key kept in Android Keystore.

If the camera is denied, type the address and code by hand. Codes work once and
expire after 2 minutes. [docs/PROTOCOL.md](docs/PROTOCOL.md) has the details.

## Build from source

You need JDK 17 and the Android SDK (compileSdk 36, minSdk 26).

```sh
git clone https://github.com/GrabLine/grabline-android.git
cd grabline-android
./gradlew assembleDebug
```

On Windows PowerShell, use `.\gradlew.bat assembleDebug`. Set `sdk.dir` in
`local.properties` if Android Studio has not done it for you.

## Testing

```sh
./gradlew testDebugUnitTest lintDebug
```

Instrumented and Compose UI tests run on an emulator. End-to-end tests against a
real PC listener use `scripts/e2e/run_e2e.py`. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Documentation

- [Product and QA spec](docs/SPEC.md)
- [Phone to PC protocol](docs/PROTOCOL.md)
- [Build plan](docs/BUILD_PLAN.md)
- [Desktop app][desktop] and the [website][website]

## Support and license

[Report a reproducible issue][issues], or [support development on Ko-fi][support].
Report vulnerabilities privately through [the security policy](SECURITY.md).

Licensed under [AGPL-3.0][license].

[website]: https://grabline.github.io/
[desktop]: https://github.com/GrabLine/GrabLine
[issues]: https://github.com/GrabLine/grabline-android/issues
[support]: https://ko-fi.com/zain021xd
[license]: LICENSE
[ci]: https://github.com/GrabLine/grabline-android/actions/workflows/android.yml
[ci-badge]: https://img.shields.io/github/actions/workflow/status/GrabLine/grabline-android/android.yml?branch=main&label=CI
[license-badge]: https://img.shields.io/badge/license-AGPL--3.0-blue

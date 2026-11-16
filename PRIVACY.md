# Privacy policy: GrabLine for Android

Last reviewed: **16 November 2026**, against the unreleased source (version 0.1.0).

GrabLine for Android has no accounts, ads or analytics, and no GrabLine
servers. Nothing is sent to the GrabLine authors. The phone never listens on a
port. This matches the Phone section of the
[desktop privacy policy](https://github.com/GrabLine/GrabLine/blob/main/PRIVACY.md#phone).

## What the phone contacts

| Who | When |
| --- | --- |
| The PCs you paired | Over your local network, using the addresses from the QR code. The phone remembers the last address that worked. |
| Download servers | Only for downloads you start on the phone: the file host, the video site, or torrent trackers, DHT and peers. Peers can see your network address. |
| File servers you add | SFTP, FTP, FTPS, WebDAV (HTTPS only) or S3: only the server your link or saved account names, on its port. S3 without a custom endpoint uses Amazon's S3 hosts. SFTP host keys are remembered on first use and a changed key stops the download. |
| yt-dlp update servers | Only for updates you approve in the app. |

The app uses the internet permission for nothing else. It has no crash
reporting, no ads SDK and no Google Play services. Opening a project or support
link contacts that site in your browser.

## What the phone stores

- The addresses of each paired PC, its certificate pin and its name.
- The phone's device key, kept in Android Keystore and not exportable.
- Saved server accounts (host, port, user name). Passwords, private keys and S3
  secrets are encrypted with a key held in Android Keystore and are never logged.
- A cached job list, queue settings and the outbox of links waiting for a PC.
- Finished and partial downloads in the folder you chose, and torrent resume data.
- Crash logs, which stay on the phone until you choose to share one.

Removing a PC in the app deletes its addresses, pin, key, cached jobs and
outbox. Uninstalling the app removes its data but not the files you downloaded.

## What the phone does not do

It never reads cookies, signed-in sessions or saved passwords from other apps
or browsers, and it never sends cookies or browser headers to the PC.

## Questions

Ask through [GitHub issues](https://github.com/GrabLine/grabline-android/issues).
Use [SECURITY.md](SECURITY.md) for a private security report. Do not attach
pairing codes, keys or crash logs you have not checked.

# Testing on a real phone

The emulator covers everything it can. These checks need a real phone, a real PC
on the same Wi-Fi, or real internet downloads. Mark each one Pass or Fail in the
QA table of the spec, with what you saw.

## Set up

1. **Phone:** Settings, About phone, tap **Build number** 7 times. Then
   Settings, System, Developer options, turn on **USB debugging**. Plug the
   phone in and accept the "Allow USB debugging" prompt.
2. **Install the app** (from the repository root):

   ```powershell
   ./gradlew installDebug
   ```

   Or install an APK you built: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. **PC:** run GrabLine from the `dev` branch (the phone remote is merged
   there). Open **Settings, Phone**, tick **Phone remote**, then press
   **Pair a phone** to show the QR code. Allow GrabLine through Windows Firewall on
   **private** networks when Windows asks.
4. **Pair:** in the app, **Devices, Pair a PC**, scan the QR code. On the PC,
   press **Allow** when it asks whether the phone may control GrabLine.

Phone and PC must be on the same Wi-Fi (not a guest network that isolates
devices). The listener never accepts connections from public addresses.

### Xiaomi, Redmi and POCO (HyperOS / MIUI)

These phones add their own blocks on top of Android:

- **Install via USB:** every `adb install` shows an "Install via USB" prompt on
  the phone that must be accepted within a few seconds. Turn on **Developer
  options, Install via USB** (it needs a Mi account), or tap **Install** each time.
- **Instrumented tests stop with "activity not started":** HyperOS blocks apps
  from opening screens in the background. Allow it for the app after each fresh
  install: `adb shell appops set dev.grabline.app 10021 allow`. Gradle's
  `connectedDebugAndroidTest` uninstalls the app first and loses this setting,
  so install both APKs and run
  `adb shell am instrument -w dev.grabline.app.test/androidx.test.runner.AndroidJUnitRunner`.
- **The phone is not listed by `adb devices` on Windows:** start adb with the
  libusb backend: `adb kill-server`, then `set ADB_LIBUSB=1` and `adb start-server`.
- **USB keeps dropping:** switch to Wi-Fi debugging: `adb tcpip 5555`, then
  `adb connect <phone-ip>:5555`.
- **Airplane mode keeps Wi-Fi on** if Wi-Fi was on before, so it does not cut the
  network for QA-19. Use `adb shell svc wifi disable` and `adb shell svc data disable`
  over USB instead.

### When the phone cannot reach the PC

QA-12 and QA-13 need the phone to connect to the PC. If Windows treats the network
as **Public**, its firewall blocks that. Either set the network to **Private** in
Windows settings, or for a USB test run `adb reverse tcp:47831 tcp:47831` and pair
with host `127.0.0.1` (`scripts/e2e/run_e2e.py --reverse`).

## Checklist

| Case | How to run it | Pass when |
| --- | --- | --- |
| QA-12 | While paired, renew the PC's IP (`ipconfig /release` then `/renew`, or reboot the router) | The phone finds the PC again within 10 s |
| QA-13 | In the YouTube app, Share, GrabLine, **PC**, Best | The job appears on the PC; the phone shows progress within 2 s |
| QA-18 | Add a 1 GB test file (for example a Linux ISO) on the **Phone** with 8 connections | The SHA-256 the app shows matches the published one |
| QA-19 | Start a large download, turn on airplane mode at about 40% for 2 minutes, turn it off | It resumes; checksum matches |
| QA-20 | At about 60%, Settings, Apps, GrabLine, **Force stop**; reopen the app | It resumes; checksum matches |
| QA-21 | Reboot the phone mid-download | It resumes after reboot; checksum matches |
| QA-28 | Turn on **Wi-Fi only**, switch Wi-Fi off (mobile data on), then back on | Pauses on mobile data; resumes on Wi-Fi |
| QA-29 | `adb shell dumpsys deviceidle force-idle` during a download, then `adb shell dumpsys deviceidle unforce` | Resumes after the idle window; checksum matches |
| QA-30 | Android 15: start a download that takes about 7 hours (or limit the speed) | Still running after 6 hours |
| QA-32 | Turn off GrabLine's notification permission, start a download | Download still runs; the app explains what is missing |
| QA-33 | Download a 1080p video on the phone | Plays, has audio, duration matches the source |
| QA-34 | Download audio only as MP3 | Plays; title and artist tags present |
| QA-35 | Download an HLS stream (`.m3u8`) | Plays from start to end |
| QA-36 | Settings, About, **Update yt-dlp** | New version shown; a video still downloads |
| QA-37 | Add a magnet on the phone (for example a Linux ISO torrent); then switch to mobile data with torrents set to Wi-Fi only | Completes with correct files; waits on mobile data |
| QA-38 | Start a phone download, then send it to the PC from its details | The phone job stops and its partial file is removed; the PC finishes it |
| QA-39 | Copy a 2 GB finished file from the PC to the phone; turn Wi-Fi off halfway, then on | It resumes; checksum matches |
| QA-41 | Turn on TalkBack and go through every screen | Every control is labelled; progress is read as percent and time left |
| QA-42 | Font size 200% and largest display size | No clipped or overlapping text |
| QA-45 | On a tablet or foldable: fold, unfold, rotate | Two-pane layout; the selection is kept |
| QA-46 | Switch the phone to Arabic | The whole layout is mirrored; sizes and dates use Arabic format |
| Cloud | Add a server under **Settings, Servers** (SFTP, FTP, WebDAV or S3) and download a file from it; pause and resume | File completes, resume continues from the offset, the password is not shown again after saving |
| Battery | Run a 1-hour download on three phone makers (for example Samsung, Xiaomi, Pixel) with battery optimisation on | The download is not killed; if it is, the battery tip in the app explains the fix |

## If something fails

Collect the log and attach it to a GitHub issue:

```powershell
adb logcat -d -v time > grabline-log.txt
```

The app never writes pairing codes, device keys or passwords to the log.

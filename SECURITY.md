# Security Policy

## Reporting a vulnerability

If you find something that lets another device on the network, a malicious
server, a torrent or a web page act outside GrabLine for Android's trust
boundaries, report it **privately** through GitHub Security Advisories:

[Open a private vulnerability report](https://github.com/GrabLine/grabline-android/security/advisories/new)
while signed in to GitHub (Security, Report a vulnerability).

Include the app version, Android version and device, a minimal reproduction,
the boundary you think is broken, the impact and sanitized evidence. Use
synthetic inputs. Do not submit credentials, pairing codes, keys or personal
downloads. There is no guaranteed response SLA.

We credit reporters who request attribution when we publish a fix or advisory.
Do not open a public issue for exploitable findings until a fix has shipped.

## Scope

The app is a single-user download manager and a remote control for the
[GrabLine desktop app](https://github.com/GrabLine/GrabLine). In scope:

- **Pairing:** guessing, reusing or replaying a pairing code; getting past the
  2-minute expiry or the 5-attempt lock; a relayed or forged proof.
- **Certificate pinning:** any way to connect to a PC whose key does not match
  the pin, including a fallback to the system trust store.
- **Device keys:** the key leaving Android Keystore, or logging in without it.
- **The local connection:** reading or changing traffic between phone and PC,
  paths instead of job ids, oversized or malformed messages.
- **No listener:** the phone must never accept an incoming connection.
- **Files:** writing outside the chosen download folder, or overwriting a file.

Content checks on downloaded files are advisory by design: the app warns and
does not quarantine. Problems in the PC listener itself belong to the
[desktop security policy](https://github.com/GrabLine/GrabLine/security/policy).
The wire protocol is documented in [docs/PROTOCOL.md](docs/PROTOCOL.md).
Never post pairing codes, keys or certificates from a real setup.

## Supported versions

No version has been released yet. Once one is, the latest published release will
be the supported one and older versions will not be patched separately. Include
the exact commit if the finding affects unreleased source.

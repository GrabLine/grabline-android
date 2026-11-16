# Releasing GrabLine for Android

Releases ship on GitHub and F-Droid first; Google Play is optional (see the risks
table in [SPEC.md](SPEC.md)).

## One-time: the release key

The owner creates the key; it never goes into the repository (`*.jks` and
`keystore.properties` are gitignored).

```powershell
./scripts/make-release-key.ps1
```

`keytool` asks for the passwords. Back up the `.jks` file and both passwords
somewhere safe: every later update must be signed with the same key.

For local signed builds, create `keystore.properties` in the repository root:

```properties
storeFile=C:/Users/you/grabline-release.jks
storePassword=...
keyAlias=grabline
keyPassword=...
```

For GitHub releases, add four repository secrets under **Settings, Secrets and
variables, Actions**: `RELEASE_KEYSTORE_BASE64` (the `.jks` file in base64),
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`.
The same values can come from the `GRABLINE_KEYSTORE`,
`GRABLINE_KEYSTORE_PASSWORD`, `GRABLINE_KEY_ALIAS` and `GRABLINE_KEY_PASSWORD`
environment variables. Without any of them, `assembleRelease` builds unsigned
APKs.

## Each release

1. Run the release checklist in [SPEC.md](SPEC.md): every QA case Pass or
   Blocked with a reason, device matrix, soak, budgets, bundled yt-dlp current.
2. Raise `versionCode` and `versionName` in `app/build.gradle.kts`.
3. Write `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (under
   500 characters; it becomes the GitHub release notes and the F-Droid
   changelog).
4. Tag and push: `git tag v0.1.0 && git push origin v0.1.0`.
5. The **Release** workflow tests, builds one signed APK per ABI, and opens a
   draft release with `SHA256SUMS.txt`. Check it, add the signing
   certificate's SHA-256 to the notes, and publish.
6. Merge the website's Android download PR once the first release is public.

## F-Droid

The store listing lives in `fastlane/metadata/android/` (title, short and full
description, icon, changelogs), which F-Droid reads from the tagged source. To
get listed, open a request at https://gitlab.com/fdroid/rfp with the
repository URL. F-Droid builds from source and signs with its own key unless
reproducible-build signing is set up; the app needs no Google Play services.

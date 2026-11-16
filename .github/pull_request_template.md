## What changes

Describe the problem and resulting behaviour. Link the related issue if present.

## Verification

List the commands or manual checks actually completed and their results.
For UI changes, include screenshots and the device or emulator used.
For anything that talks to a PC, say which GrabLine desktop version you tested.
Record unavailable device, account or network checks explicitly.

## Review checklist

- [ ] The change is focused and preserves unrelated work.
- [ ] `./gradlew assembleDebug testDebugUnitTest lintDebug` passes.
- [ ] Strings are in `strings.xml`, with content descriptions and 48 dp tap targets.
- [ ] No secrets, keystores, `local.properties` or build output are included.
- [ ] Any change to pairing, auth, TLS or keys has tests and a security review.
- [ ] If the protocol changed, the desktop repo changed first and fixtures are synced.

Target `main`.

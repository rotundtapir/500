<!-- SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception -->
# Invite links & deep linking

The host of an online lobby can share an invite link that opens the game for whoever taps it:

```
https://rotundtapir.github.io/500/?joinCode=12AB
```

(`joinCode` is the room's 4-character code, always uppercase.) The link is built by
`JoinLink.forCode` in `:shared`; the Share button on the lobby screen uses the `LinkSharer` seam —
Android's native share sheet, or copy-to-clipboard on web.

## What happens when the link is opened

- **In a browser** — the web app reads `?joinCode=` (`web/.../Main.kt`), enters online mode, and
  lands on the **Join** screen with the code prefilled. The player sets/confirms their name and taps
  Join. Works with no install; nothing else to configure.
- **In the installed Android app** — the same URL is registered as an
  [Android App Link](https://developer.android.com/training/app-links) (see the `VIEW` intent-filter
  in `app/src/main/AndroidManifest.xml`). `MainActivity` reads `intent.data`'s `joinCode` (on cold
  start and via `onNewIntent`, since the activity is `singleTask`) and routes to the same prefilled
  Join screen. The player always chooses their name — we never auto-join.

## Making Android open the link "by default" (App Links verification)

For Android to open the link in the app automatically (instead of the browser), the domain must
publish a **Digital Asset Links** file that authorises this app. This is a one-time hosting step,
**not** done by this repo's CI, because:

1. **It must be served from the domain root**, i.e.
   `https://rotundtapir.github.io/.well-known/assetlinks.json` — the host root, path ignored.
   This repo's Pages site is published under the `/500/` subpath, so the root
   `rotundtapir.github.io/.well-known/…` is served by the **separate `rotundtapir.github.io`
   user-site repo**. Copy `docs/assetlinks.json` there (creating `.well-known/assetlinks.json`).
2. **It must list the app's signing SHA-256 fingerprint(s).** `docs/assetlinks.json` has
   placeholders. Fill in:
   - the **Play App Signing** key SHA-256 (Play Console → *Test and release → App integrity → App
     signing key certificate*) — required because Play re-signs installs from the store;
   - the **upload / local-release** key SHA-256 for sideloaded GitHub-release APKs. Get it with
     `keytool -list -v -keystore <release.jks> -alias <alias>` or `./gradlew :app:signingReport`.
   You can list several fingerprints; include every key that signs a shipped build.

Verify once published:

```bash
curl https://rotundtapir.github.io/.well-known/assetlinks.json      # returns the JSON above
# on a device with the app installed:
adb shell pm verify-app-links --re-verify io.github.rotundtapir.fivehundred
adb shell pm get-app-links io.github.rotundtapir.fivehundred        # domain shows "verified"
```

Until this is in place the intent-filter still works, but Android won't auto-open the link — the
user can enable it under *Settings → Apps → 500 → Open by default → Add link*, or the link simply
opens in the browser (where the web app handles the join anyway).

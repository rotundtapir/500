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
  Join. Works with no install.
- **In the installed Android app** — the same URL is registered as an
  [Android App Link](https://developer.android.com/training/app-links) (the `VIEW` intent-filter in
  `app/src/main/AndroidManifest.xml`). `MainActivity` reads `intent.data`'s `joinCode` (on cold
  start and via `onNewIntent`, since the activity is `singleTask`) and routes to the same prefilled
  Join screen. The player always chooses their name — we never auto-join.

## App Links verification (set up)

Android opens the link in the app "by default" because the domain publishes a Digital Asset Links
file authorising this app. It is **already hosted** at
<https://rotundtapir.github.io/.well-known/assetlinks.json> (served by the `rotundtapir.github.io`
user-site repo — the app's Pages site lives under `/500/`, so the domain root is a separate repo).
It authorises `io.github.rotundtapir.fivehundred` for three signing certs, so every channel opens
links: **Play App Signing**, the **FOSS release** key (sideloaded GitHub-release APKs), and the
**debug** key (local dev builds).

Maintenance — update `.well-known/assetlinks.json` in that repo if a signing key changes:

```bash
# a shipped APK's signing cert SHA-256 (works for any channel):
apksigner verify --print-certs app-foss-release.apk        # from a GitHub release
adb shell pm path io.github.rotundtapir.fivehundred        # then pull base.apk from a device
# the Play App Signing SHA-256 is also in Play Console → Test and release → App integrity.

# check it end to end:
curl https://rotundtapir.github.io/.well-known/assetlinks.json
adb shell pm get-app-links io.github.rotundtapir.fivehundred   # domain shows "verified"
```

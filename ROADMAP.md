<!-- SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception -->

# Roadmap

Direction, not commitment — items land when they're ready. Feedback and votes:
[issues](https://github.com/rotundtapir/500/issues).

## Shipped

- **v0.1 — offline 500 against bots.** 2/4/6 players, misère & no-trumps house
  rules, interactive tutorial, FOSS + Play flavors.
- **v0.2 — web build.** The same game playable in any modern browser
  (Kotlin/Wasm) at <https://rotundtapir.github.io/500/>, deployed to GitHub
  Pages on release tags.
- **v0.3 — online multiplayer.** An authoritative hosted server (the `:server`
  module) that runs the same `GameDriver` one room at a time, with the wire
  protocol and client in `:net` and the lobby/online UI in `:shared`. Invite-code
  lobbies, all four table shapes, bot fill-in, disconnect→bot substitution with
  session-token seat reclaim, canned emotes, cross-play (Android ↔ web), a
  configurable/self-hostable server URL, and CI deployment to the VPS on `v*`
  tags. See `docs/multiplayer-architecture.md` and `docs/self-hosting.md`.
- **v0.4 — Advanced AI.** An opt-in Monte-Carlo search bot for stronger local
  opponents (Settings → Advanced AI; off by default, local games only), plus
  the hand-result banner naming the declarer and bid.
- **v0.4.1 / v0.4.2 — restart-safe online games**
  ([#16](https://github.com/rotundtapir/500/issues/16)). Room snapshots on
  disk, so a server deploy or crash no longer drops in-flight games: everyone
  rejoins their seat and play carries on. Billing-first startup in the Play
  flavor, so a `remove_ads` purchase keeps the ads SDK from ever initialising
  ([#17](https://github.com/rotundtapir/500/issues/17), code side).
  Reproducible FOSS builds, verified in CI on every tag
  ([#22](https://github.com/rotundtapir/500/issues/22)).
- **v0.5 — on F-Droid**
  ([#18](https://github.com/rotundtapir/500/issues/18)), publishing our own
  signature. Online play grew up: a 3-minute grace before a bot covers a seat
  you left, relaxed 30-minute turn timers, one-tap Ready, faster reconnects.
- **v0.6 — landscape and a toolbox.** A height-adaptive game screen — a phone
  in landscape (and the web build in a landscape window) gets a real side-column
  layout instead of a clipped hand
  ([#41](https://github.com/rotundtapir/500/issues/41)); the "Update required"
  dialog names both versions and offers the right update route
  ([#49](https://github.com/rotundtapir/500/issues/49)); a hidden cheats menu
  for local games ([#51](https://github.com/rotundtapir/500/issues/51));
  refreshed store screenshots ([#50](https://github.com/rotundtapir/500/issues/50));
  a landing page at <https://29022617.xyz>
  ([#37](https://github.com/rotundtapir/500/issues/37)). The 0.6.x patch
  releases made every dialog's way out reachable on short windows, stopped a
  rotation resurrecting a dismissed hand result, and moved the Play flavor to
  Billing 8 / targetSdk 36. Play uploads are automated: an `rc/<version>-<n>`
  tag ships the bundle to Play's internal track, the matching `v<version>` tag
  publishes everywhere else (see `CLAUDE.md`, "Releasing").

## Towards v1.0

- **Google Play production release**
  ([#19](https://github.com/rotundtapir/500/issues/19)). The pipeline into
  internal testing is automatic now; what remains is human: the store listing
  and data-safety declarations (`docs/play-console.md`), and a closed beta with
  enough opted-in testers to meet Google's production-access requirement.

- **Real-device purchase QA for the Play flavor**
  ([#17](https://github.com/rotundtapir/500/issues/17)). The billing-first
  code has shipped; still owed is a Play-Store device with a license tester
  exercising a real purchase, a reinstall restore, and a refund.

- **Drop the portrait lock on Android.** The v0.6 layout handles landscape,
  and the 0.6.x dialogs are safe in short windows, but the felt still
  collapses to a sliver during bidding at phone-landscape heights. Fix that
  and the `userPortrait` stopgap can go (it is already ignored on large
  screens at targetSdk 36).

## After v1.0

- Drop the debug-keystore fingerprint from `assetlinks.json`
  ([#38](https://github.com/rotundtapir/500/issues/38)).
- A public lobby browser for online play (invite codes only today).
- Further bot strength beyond the v0.4 Advanced AI: card counting / inference
  from the auction and discards, and smarter partner cooperation in the
  default heuristic bot (must stay deterministic per seed and fast on modest
  phones); Advanced AI for online bot seats.
- Statistics / match history.
- Tablet layout polish (`fivehundred_tablet` AVD exists for testing) — more
  pressing now that tablets and unfolded foldables run landscape.

## Done elsewhere

- The generic online parts moved into cardkit (`cardkit-net`, `cardkit-server`)
  once a second game needed them; **Euchre** is that second game, on the same
  base.

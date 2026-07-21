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

## Towards v1.0

- **Rejoin that survives a server restart**
  ([#16](https://github.com/rotundtapir/500/issues/16)). Server state is
  in-memory today, so a deploy or crash drops every in-flight game. Persist
  room snapshots (the engine state is a serializable pure state machine) so
  clients can reclaim their seats with their existing session tokens.

- **Remove-ads purchase fully disables the ads SDK** (play flavor,
  [#17](https://github.com/rotundtapir/500/issues/17)). Today the
  purchase stops ads from loading or showing, but the Google Mobile Ads SDK is
  still initialised after consent, so initialisation traffic still flows.
  Resequence startup billing-first: check the `remove_ads` entitlement before
  gathering consent or touching the SDK, so paying users' devices never talk to
  ad servers at all. Touches the consent-before-ads and exactly-once
  interstitial invariants in `cardkit-monetization-play` — needs careful
  emulator verification of first-launch, purchase, and reinstall flows.

- **F-Droid submission**
  ([#18](https://github.com/rotundtapir/500/issues/18)). Get the `foss`
  flavor listed: fdroiddata recipe, clean-checkout build verification, and
  the inclusion review.

- **Google Play production release**
  ([#19](https://github.com/rotundtapir/500/issues/19)). From internal
  testing to production: updated data-safety declarations for multiplayer,
  store listing, and a closed beta with enough opted-in testers to meet
  Google's production-access requirement.

## Unscheduled ideas

- A public lobby browser for online play (invite codes only today).
- Extracting the generic online parts into `cardkit-server` /
  `cardkit-net-client` — deferred until a second game uses cardkit, so real
  usage shows which seams deserve extraction.
- Further bot strength beyond the v0.4 Advanced AI: card counting / inference
  from the auction and discards, and smarter partner cooperation in the
  default heuristic bot (must stay deterministic per seed and fast on modest
  phones); Advanced AI for online bot seats.
- More games on the shared `cardkit` base (Euchre is the natural next — the
  bower logic already generalises).
- Statistics / match history.
- Tablet layout polish (`fivehundred_tablet` AVD exists for testing).

<!-- SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception -->

# Roadmap

Direction, not commitment — items land when they're ready. Feedback and votes:
[issues](https://github.com/rotundtapir/500/issues).

## v0.1 — first release (in progress)

Offline 500 against bots: 2/4/6 players, misère & no-trumps house rules,
interactive tutorial, FOSS + Play flavors. Play internal testing underway;
F-Droid submission follows the first stable tag.

## Towards v1.0

- **Remove-ads purchase fully disables the ads SDK** (play flavor). Today the
  purchase stops ads from loading or showing, but the Google Mobile Ads SDK is
  still initialised after consent, so initialisation traffic still flows.
  Resequence startup billing-first: check the `remove_ads` entitlement before
  gathering consent or touching the SDK, so paying users' devices never talk to
  ad servers at all. Touches the consent-before-ads and exactly-once
  interstitial invariants in `cardkit-monetization-play` — needs careful
  emulator verification of first-launch, purchase, and reinstall flows.

- **Online multiplayer** *(landed — being stabilised)*. Implemented as the
  authoritative hosted server the analysis (`docs/multiplayer-architecture.md`)
  recommended: a Ktor/WebSocket server (`:server`) that runs the same
  `GameDriver` one room at a time, with the wire protocol and client in a new
  `:net` module and the lobby/online UI in `:shared`. Invite-code lobbies, all
  four table flavours, bot fill-in, disconnect→bot substitution with
  session-token seat reclaim, canned emotes, a configurable/self-hostable server
  URL, and CI deployment to the VPS on `v*` tags. Still to do: extract the
  generic parts into `cardkit-server` / `cardkit-net-client`, a public lobby
  browser, chat beyond emotes, and cross-process-death rejoin.

- **Stronger bot AI.** The current `FiveHundredBot` is heuristic (bid
  estimation, misère defence, simple card-play rules). Candidates, roughly in
  order of value: card counting / inference from the auction and discards,
  smarter partner cooperation on defence, Monte Carlo simulation of hidden
  hands for play decisions, and difficulty levels so the default stays
  approachable. Must stay deterministic per seed (the engine's reproducibility
  guarantee) and fast enough for 6-player games on modest phones.

## Unscheduled ideas

- More games on the shared `cardkit` base (Euchre is the natural next — the
  bower logic already generalises).
- Statistics / match history.
- Tablet layout polish (`fivehundred_tablet` AVD exists for testing).

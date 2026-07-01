# 500

An Android app for **500**, the classic 4-player partnership trick-taking card
game (Australian rules). Play against three AI opponents offline.

Built on the shared [`cardkit`](https://github.com/rotundtapir/cardkit) library
(included here as a git submodule), the first of a suite of card-game apps.

## Distribution

| Channel | Ads | Support |
| --- | --- | --- |
| **Google Play** | Banner + occasional interstitial (Google Mobile Ads), with a one-time in-app purchase to remove them | purchase |
| **F-Droid / GitHub releases** | **None** — no ads, no trackers, no proprietary code | [donation link](.github/FUNDING.yml) |

The two are the `play` and `foss` build flavors. The F-Droid build excludes the
`cardkit-monetization-play` module entirely, so it contains no non-free code.

## Building

```bash
git clone --recurse-submodules <repo-url>
cd 500
./gradlew :engine:test          # run the rules engine unit tests (JDK 21, no Android SDK needed)
./gradlew assembleFossDebug     # ad-free debug APK
./gradlew assemblePlayDebug     # ad-supported debug APK
```

Requires **JDK 21** and, for the app modules, the **Android SDK** (`compileSdk 35`).

## Project layout

```
500/
├── cardkit/     # shared library (git submodule), wired in via includeBuild
├── engine/      # pure-Kotlin 500 rules: deck, bidding, tricks, scoring
├── ai/          # heuristic bot strategy
└── app/         # Jetpack Compose UI; foss/play flavors
```

The rules engine is pure Kotlin with no Android dependency, so it is fully
unit-tested and can run server-side if online multiplayer is added later.

## The game

Standard 4-player Australian 500: a 43-card deck (4→A in hearts & diamonds,
5→A in spades & clubs, plus one Joker), 10 cards each and a 3-card kitty;
bidding from 6 to 10 tricks across the suits and no-trumps, plus Misère and Open
Misère; first partnership to 500 points wins.

## License

GPLv3-or-later **with** a Google Mobile Ads / Play Billing linking exception —
see [`LICENSE`](LICENSE) and [`LICENSE-EXCEPTION.md`](LICENSE-EXCEPTION.md).
Contributions require a DCO sign-off; see [`CONTRIBUTING.md`](CONTRIBUTING.md).

> **Namespace note:** `io.github.rotundtapir.*` is a placeholder based on the
> GitHub-account convention. Set it to your real GitHub username/org (and pick
> the final `applicationId`) before the first release.

# 500

An **Android and web** app for **500**, the classic 4-player partnership
trick-taking card game (Australian rules). Play offline against AI opponents, or
**online with friends** — cross-play between Android and the browser, with bots
filling any empty seats. Play it now in your browser: **https://rotundtapir.github.io/500/**

Built on the shared [`cardkit`](https://github.com/rotundtapir/cardkit) library
(included here as a git submodule), the first of a suite of card-game apps.
Where it's heading: [ROADMAP.md](ROADMAP.md).

## Distribution

| Channel | Ads | Support |
| --- | --- | --- |
| **Google Play** | Banner + occasional interstitial (Google Mobile Ads), with a one-time in-app purchase to remove them | purchase |
| **F-Droid / GitHub releases** | **None** — no ads, no trackers, no proprietary code | [donation link](.github/FUNDING.yml) |

The two are the `play` and `foss` build flavors. The F-Droid build excludes the
`cardkit-monetization-play` module entirely, so it contains no non-free code.

## Install / play

- **Web**: play instantly at <https://rotundtapir.github.io/500/> — no install.
- **GitHub**: signed ad-free APKs are attached to
  [releases](https://github.com/rotundtapir/500/releases) (install directly;
  updates keep the same signature).
- **Google Play**: internal testing (ad-supported flavor).
- **F-Droid**: [![F-Droid](https://img.shields.io/f-droid/v/io.github.rotundtapir.fivehundred?logo=fdroid)](https://f-droid.org/packages/io.github.rotundtapir.fivehundred/)

Support development via [Liberapay](https://liberapay.com/rotund-tapir).

### One signature everywhere

The `foss` release build is [reproducible](https://f-droid.org/en/docs/Reproducible_Builds/):
a clean checkout of a release tag rebuilds the GitHub-release APK bit-for-bit
(only the signing block differs). The F-Droid listing uses this so
F-Droid can verify its own rebuild against our release asset and publish the
**developer-signed** APK — the same file as on GitHub releases, so you can
switch install sources without uninstalling. The release signing certificate's
SHA-256 fingerprint is:

```
86c486105ed5062b90815fec97311029ab5569735926c323c7896c8d64b78d65
```

## Building

```bash
git clone --recurse-submodules <repo-url>
cd 500
./gradlew :engine:jvmTest       # run the rules engine unit tests (JDK 21, no Android SDK needed)
./gradlew :server:test          # online server tests (JVM; real-WebSocket integration)
./gradlew assembleFossDebug     # ad-free debug APK
./gradlew assemblePlayDebug     # ad-supported debug APK
./gradlew :web:wasmJsBrowserRun # run the web build locally (http://localhost:8080)
```

Requires **JDK 21** and, for the app modules, the **Android SDK** (`compileSdk 36`).

## Project layout

```
500/
├── cardkit/     # shared library (git submodule), wired in via includeBuild
├── engine/      # pure-Kotlin 500 rules: deck, bidding, tricks, scoring
├── ai/          # heuristic bot strategy
├── net/         # online wire protocol + client (KMP jvm+wasmJs)
├── server/      # authoritative online server (JVM Ktor); Docker/deploy in server/deploy
├── shared/      # Compose Multiplatform game UI (offline + online), android+wasmJs
├── app/         # Android shell; foss/play flavors
└── web/         # browser (Kotlin/Wasm) shell
```

The rules engine is pure Kotlin with no Android dependency, so it is fully
unit-tested and runs server-side — which is exactly what the online server does,
driving the same engine one game room at a time.

## The game

Standard 4-player Australian 500: a 43-card deck (4→A in hearts & diamonds,
5→A in spades & clubs, plus one Joker), 10 cards each and a 3-card kitty;
bidding from 6 to 10 tricks across the suits and no-trumps, plus Misère and Open
Misère; first partnership to 500 points wins.

## License

GPLv3-or-later **with** a Google Mobile Ads / Play Billing linking exception —
see [`LICENSE`](LICENSE) and [`LICENSE-EXCEPTION.md`](LICENSE-EXCEPTION.md).
Contributions require a DCO sign-off; see [`CONTRIBUTING.md`](CONTRIBUTING.md).

> **Namespace:** `io.github.rotundtapir.*` (and the `applicationId`
> `io.github.rotundtapir.fivehundred`) is the `io.github.<account>` namespace
> of the GitHub account [`rotundtapir`](https://github.com/rotundtapir) that
> publishes this app.

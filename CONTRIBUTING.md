# Contributing to 500

Thanks for your interest! This is the Android app for the card game **500**,
built on the shared [`cardkit`](cardkit) library (included as a git submodule).

## Getting the source

```bash
git clone --recurse-submodules <repo-url>
# or, if you already cloned without submodules:
git submodule update --init --recursive
```

The `cardkit` submodule is wired in as a Gradle composite build, so a normal
`./gradlew` build compiles the library from source alongside the app.

## License of contributions

This project is licensed under the **GNU General Public License v3.0 or later,
WITH** the Google Mobile Ads / Play Billing linking exception described in
[`LICENSE-EXCEPTION.md`](LICENSE-EXCEPTION.md).

**Inbound = outbound:** by submitting a contribution you agree it is licensed
under exactly those terms. This lets contributed code ship in both the
free/libre (F-Droid) build *and* the ad-supported (Google Play) build without a
separate CLA.

### Developer Certificate of Origin (DCO)

We use the [DCO](https://developercertificate.org/) instead of a CLA. Sign off
every commit:

```bash
git commit -s -m "your message"
```

Commits without a `Signed-off-by` trailer will not be merged.

## Where code goes

- **`engine/`** — pure Kotlin/JVM 500 rules (deck, bidding, trick evaluation,
  scoring). No Android APIs; keep it deterministic and unit-tested.
- **`ai/`** — bot strategy, pure Kotlin/JVM.
- **`app/`** — the Android app (Jetpack Compose UI, ViewModels). Ads and billing
  live only in the `play` source set / the `cardkit-monetization-play` module,
  never in shared code.
- Reusable, game-agnostic infrastructure belongs in `cardkit`, not here.

Add a SPDX header to new source files:
`// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception`

## Building

```bash
./gradlew :engine:test              # fast, pure-Kotlin unit tests
./gradlew assembleFossDebug         # ad-free build
./gradlew assemblePlayDebug         # ad-supported build
```

Requires JDK 21 and the Android SDK (`compileSdk 35`).

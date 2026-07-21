# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

The Android **and web (Kotlin/Wasm)** app for the card game **500** (4-player Australian rules), and
the first consumer of the shared **`cardkit`** library. `cardkit` lives in its own repo and is
included here as a git submodule at `./cardkit`, wired into Gradle as a composite build. Only
500-specific code lives in this repo; game-agnostic infrastructure lives in `cardkit`. The web build
deploys to GitHub Pages (https://rotundtapir.github.io/500/) on `v*` release tags.

Since **v0.3.0** the app also has **online multiplayer**: an authoritative hosted server (the `:server`
module) that runs the same engine, with the wire protocol/client in `:net` and the lobby/online UI in
`:shared`. Cross-play between Android (both flavors) and web; play offline vs bots or online with
friends. The official server runs on a VPS behind Caddy and is deployed by CI on `v*` tags; it's also
self-hostable (see `docs/self-hosting.md`).

## Toolchain (read first — non-obvious and will waste time otherwise)

- **Gradle must run on JDK 21** (the machine default `java` is JDK 25, and the Android Gradle Plugin
  fails on JDK 25+). `gradle/gradle-daemon-jvm.properties` pins the daemon to a version-21 toolchain
  (vendor-agnostic — do NOT let Android Studio regenerate it with `toolchainVendor=JETBRAINS`; that
  breaks CI, which has Temurin). So `JAVA_HOME` is no longer required for `./gradlew`, but every
  invocation still needs:
  ```bash
  export ANDROID_HOME="$HOME/Android/Sdk"
  ```
  Kotlin/JVM modules pin `jvmToolchain(21)`; Android modules pin `jvmTarget = 17`.
- `gradle` is not on PATH; use the committed wrapper `./gradlew` (or `source ~/.sdkman/bin/sdkman-init.sh`).
- The Android SDK here has `platforms;android-36` + `build-tools;36.0.0` (compileSdk 36).

### Cloud containers (Claude Code on the web / sandboxed remote sessions)

Egress-restricted containers often can't run Gradle at all — diagnose fast and fall back to CI
instead of burning time:

- The wrapper's distribution download (github.com) may be blocked. A system Gradle usually exists
  (`/opt/gradle/bin/gradle` or under `~/.sdkman`); the daemon-JVM pin still applies, so it works
  the same as the wrapper.
- If **dl.google.com** (Google's Maven repo) is blocked, the Android Gradle Plugin can't be
  resolved and **no task in this build configures — not even pure-JVM ones** like
  `:engine:jvmTest` (settings.gradle.kts configures every module). Check with
  `curl -sI https://dl.google.com/dl/android/maven2/`; a 403 from the proxy means give up on
  local Gradle entirely.
- **Fallback: run Gradle via GitHub Actions.** Commit, push the branch, and open a (draft) PR —
  `ci.yml` runs on every `pull_request` and covers the full gate: `qualityCheck`, all unit
  tests + lint, kover, both flavors, web dist, web e2e, the FOSS-purity grep, and the emulator
  suite (`android-e2e`). There is no `workflow_dispatch` or feature-branch push trigger, so a PR
  is the only way to get CI onto a branch. Say explicitly in the PR that local verification
  wasn't possible and CI is the check.
- Cloud sessions have no `gh` CLI; watch runs and read job logs through the GitHub MCP tools.

## Common commands

```bash
# Pure-Kotlin logic (fast, no Android SDK needed) — the engine is the important part.
# engine/ai/net/cardkit-core are Kotlin Multiplatform (jvm + wasmJs); unit tests live in jvmTest.
./gradlew :engine:jvmTest
./gradlew :ai:jvmTest
./gradlew :net:jvmTest                    # wire-protocol golden/round-trip + Names validation

# Online server (JVM). Unit + real-WebSocket integration tests (full bot-backed games, reconnect,
# rematch, timeouts, abuse limits). Opt-in live smoke test against a deployed server:
#   ./gradlew :server:test -Dvps.url=wss://500.29022617.xyz/ws   (skipped without -Dvps.url)
./gradlew :server:test
./gradlew :server:run                     # run the server locally (DEV_MODE=true relaxes limits)
./gradlew :server:installDist             # what the Docker image / web online-e2e consume

# A single test class (JUnit 5 platform)
./gradlew :engine:jvmTest --tests "io.github.rotundtapir.fivehundred.engine.TrickEvaluatorTest"

# Build both distribution flavors
./gradlew assembleFossDebug assemblePlayDebug

# Lint + static analysis (CI's `build` runs `qualityCheck lint`; the pre-commit hook runs
# `qualityCheck lint jvmTest`). qualityCheck = detekt + CPD — the local `lint` task is Android-only,
# so run qualityCheck too or detekt-only findings slip through to CI.
./gradlew qualityCheck lint

# On-device integration tests (Compose UI driving real games; ~2 min on the emulator).
# Pin the serial or the task also grabs (and later uninstalls from) any attached phone.
# OnlineFlowTest needs a host-run server (DEV_MODE=true ./gradlew :server:run) — it self-skips
# without one. CI runs this suite too (the android-e2e job, server started first); see
# docs/e2e-coverage.md for the per-flow E2E matrix across web/Android/server.
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedFossDebugAndroidTest

# CRITICAL F-Droid gate: the FOSS build must contain NO proprietary dependency.
./gradlew :app:dependencies --configuration fossDebugRuntimeClasspath \
  | grep -Ei 'gms|billing|firebase|monetization-play'   # must print nothing

# Web (Kotlin/Wasm): dev server with live reload, and the static production site
# (build/dist/wasmJs/productionExecutable — what the Pages deploy publishes).
./gradlew :web:wasmJsBrowserRun          # http://localhost:8080
./gradlew :web:wasmJsBrowserDistribution
# Web analogue of the test intent extras: ?seed=42&animationSpeed=OFF&soundVolume=0

# Web E2E (Playwright over the production dist, served under the Pages /500/ prefix).
# Build BOTH the web distribution AND :server:installDist first — the online spec boots a real
# server (playwright.config webServer). Uses system Chrome (channel: 'chrome'), no browser download.
cd web/e2e && npm ci && npx playwright test
# Locate semantically but click via page.mouse at the locator's box centre — the canvas
# intercepts pointer events, so plain .click() fails actionability. Playable cards are
# a11y buttons ("Q♣"); unplayable cards are imgs.
```

Enable the pre-commit hook once per clone: `git config core.hooksPath scripts/hooks` (runs
`./gradlew qualityCheck lint jvmTest`; skips doc-only commits; auto-selects JDK 21; bypass with
`--no-verify`).

## Architecture

### Module layout (this repo + the submodule)
Most modules are Kotlin Multiplatform; `wasmJs` is the browser target throughout.
- `cardkit/` (submodule) — reusable infra: `cardkit-core` (pure Kotlin, jvm+wasmJs), `cardkit-ui`
  (Compose Multiplatform, android+wasmJs), `cardkit-monetization` (interface + FOSS/browser no-ops),
  `cardkit-monetization-play` (Google Ads + Billing, Android-only).
- `engine/` — pure-Kotlin 500 rules, KMP jvm+wasmJs. **No Android or JVM-only imports** (the wasm
  target won't compile a leak). This keeps the authoritative engine runnable server-side — which
  online multiplayer now does. Unit tests live in `src/jvmTest`.
- `ai/` — heuristic bot, pure Kotlin (KMP jvm+wasmJs), depends on `engine`.
- `net/` — the online wire protocol + client, KMP jvm+wasmJs, no Android/proprietary deps (reaches
  both flavors and web). `Protocol.kt` (sealed `ClientMessage`/`ServerMessage` with stable
  `@SerialName`s), `WireJson`, `Names` validation, and `GameClient`/`KtorGameClient` (expect/actual
  Ktor client: CIO on jvm, Js on wasm).
- `server/` — the authoritative online server, **JVM only** (Ktor CIO WebSocket app). Actor-per-`Room`
  running the same `GameDriver`, `SeatHost` (the server-side `Player`, mirrors `ChannelPlayer`),
  `SessionRegistry` (reconnect tokens), `RoomRegistry` (4-char join codes), anti-abuse (per-IP caps,
  rate limits, origin check), `/health` + `/metrics`, graceful drain/shutdown. Live state is
  in-memory; with `DATA_DIR` set (the deployed default, volume `/data`) rooms are also snapshotted
  to disk (`RoomPersistence`) and restored at boot, so a restart/deploy no longer drops games —
  clients rejoin their seats via their session tokens. A restored game holds its `GameDriver` until
  the first owner reconnects (otherwise bots would race it to completion). Depends on `engine`/`ai`/`net`; no Compose, no Android. See also
  `docs/multiplayer-architecture.md`, `docs/self-hosting.md`, `docs/server-runbook.md`.
- `shared/` — the whole game UI (screens, `GameViewModel`, tutorial, and the online flow —
  `OnlineViewModel`/`OnlineGameSession` + `ui/online/`) as Compose Multiplatform common code,
  android+wasmJs. Platform seams: `SettingsRepository` (interface; DataStore impl in its androidMain,
  localStorage impl in `web/`) and `AppConfig`/`LocalAppConfig` (replaces BuildConfig in shared code).
- `app/` — the Android shell: `MainActivity` (intent-extra test overrides, incl. `EXTRA_SERVER_URL`),
  flavors, monetization providers. Depends on `shared`.
- `web/` — the browser shell: `ComposeViewport` entry, URL-param test overrides,
  `LocalStorageSettingsRepository`, `BrowserMonetization` wiring, and a DejaVu Sans symbol-subset
  fallback font (the wasm canvas has no system fonts — without it, ♠♥♦♣/⇄/⚙ render as tofu).

### The engine is a pure state machine (the core idea)
`FiveHundredRules : GameRules<GameState, Action, PlayerView>` (cardkit-core interface) — `apply(state,
seat, action)` is a pure reducer; `GameDriver` loops it, asking each seat's `Player` to decide.
- **Determinism:** the whole match derives from `rngSeed` in `GameState`, which evolves per deal. Same
  seed ⇒ identical match. Tests and reproducibility depend on this — don't introduce nondeterminism.
  One deliberate exception: the opt-in Advanced AI (`ai/AdvancedBot`, a wall-clock-budgeted Monte
  Carlo search) is nondeterministic across devices in production; tests pin it down with
  `SearchConfig(timeBudgetEnabled = false)` (fixed iterations ⇒ pure function of seed). The default
  bot, the tutorial, and the engine itself stay strictly seed-deterministic.
- **`PlayerView` is redacted per-seat** (own hand + public info only). This is the multiplayer seam:
  `Player` is `suspend`, so a local AI (`StrategyPlayer`), a human (`ChannelPlayer`, driven by the UI),
  or a future `RemotePlayer` are interchangeable with no engine change. Never widen `PlayerView` to
  expose hidden hands.
- **Bidding** ranks via `ScoreSchedule.ladder` (an ordered list), NOT by point value — Misère (250)
  sits between 8♠ and 8♣, and Open Misère outranks 10NT despite tying its 500 points.
- **`TrickEvaluator`** owns all card-strength logic: trumps, both bowers (the left bower — Jack of the
  same-colour suit — counts as trump), the Joker, no-trump, and follow-suit legality. Cards are
  deliberately not `Comparable`; strength is always relative to (trump, ledSuit).

### UI pacing is signal-driven, and tests turn it off (don't break either)
`GameViewModel` paces bots with **signals, not timers**: bot turns await `dealAnimationDone`,
`trickAcked` (with `holdTricks` — a held trick releases live when the toggle flips off), and
`handResultAcked` StateFlows that the UI raises. **Every pacing mechanism must be inert at
`AnimationSpeed.OFF`** — the 22-test connected suite (`GameFlowTest`) depends on it, pinning
`EXTRA_SEED=42`, `EXTRA_ANIMATION_SPEED="OFF"` and `EXTRA_SOUND_VOLUME=0f` via intent extras
(volume 0 also means the SoundPool is never created — native audio playback crashes the
instrumented process on the `-no-audio` emulator).

The interactive tutorial replays the scripted hand of `TUTORIAL_SEED = 9` through the normal
ViewModel wiring; the exact trace is documented in `ui/Tutorial.kt` and generated by a `@Disabled`
test in `ai/`. If the seed's trace changes (engine/bot changes), regenerate both. The tutorial
forces trick-holding on and deliberately never acknowledges its hand result, so the finished board
stays put behind the epilogue.

Sound: the `SoundPool` engine (`SoundManager`/`rememberSoundManager` + CC0 assets) lives in
cardkit-ui; this repo only maps `PlayerView` transitions to effects (`Sounds.kt`) and fires
deal/shuffle sounds from `DealAnimationState.soundHook`.

### Distribution flavors & monetization (the reason for the module split)
Two flavors on dimension `distribution`: **`foss`** (no ads, donation link; what F-Droid builds) and
**`play`** (Google Ads + a remove-ads IAP). Shared code only references the `Monetization` interface;
the concrete impl is chosen by a **flavor-specific `MonetizationProvider`** in `app/src/foss` vs
`app/src/play`. All proprietary code is quarantined in the `cardkit-monetization-play` module, which
**only the `play` flavor depends on** (`"playImplementation"(...)`), so the FOSS build graph is
provably free of non-free code. Do not add GMS/Billing/Firebase anywhere the `foss` build can reach.

Play-flavor specifics: startup is billing-first — a cached `remove_ads` entitlement skips UMP
consent and the ads SDK entirely (paying users' devices never talk to ad servers), and with no
cache, consent waits briefly for Play Billing's verdict. Otherwise UMP/GDPR consent is gathered
before any ad (debug builds force EEA geography so the form is always exercisable); settings shows
a "Privacy options" button when the CMP requires one. The interstitial shows **once per game** — on "Back to menu" from the win/lose dialog —
and the exit waits for `maybeShowInterstitial`'s `onDismissed` continuation, so nothing animates
under an ad. Keep it that way for any future ad moment.

## Working across the submodule

Editing shared/infra behaviour means changing files under `cardkit/`, which is a **separate repo**:
1. Commit inside `cardkit/` (its own history, license, and pre-commit hook).
2. Advance the submodule pointer in this repo: the submodule's origin is the (not-yet-pushed) GitHub
   URL, so to pull a locally-made cardkit commit into `./cardkit` use
   `git -C cardkit fetch /path/to/cardkit main && git -C cardkit checkout <sha>`, then
   `git add cardkit` and commit here. Push `cardkit` before pushing this repo so the referenced commit
   exists remotely.

## Conventions

- New source files get the SPDX header:
  `// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception`
- Commits require a DCO sign-off (`git commit -s`); no CLA.
- Merge PRs by fast-forward (or GitHub's rebase merge when the branch is behind `main`) — keep
  `main`'s history linear; no merge commits.
- Namespace is `io.github.rotundtapir.*` (final — matches the `rotundtapir` GitHub account, which
  was renamed from `rotund-tapir` on 2026-07-06 precisely so the namespace is literally true).
  `applicationId` is `io.github.rotundtapir.fivehundred`.
- Monetization ids are real: the AdMob app id lives in `app/src/play/AndroidManifest.xml`; the play
  `MonetizationProvider` uses Google's test ad units in debug builds and the real units in release
  (never point debug builds at the real units — invalid-traffic risk). The `remove_ads` Play Console
  product and the Liberapay donation URL match what's in code. Store listing title is fastlane's
  `title.txt` ("500 - Card game"); the launcher label stays "500".
- **Lint gotcha (AGP 8.7):** conditional `Modifier.then(if (…) Modifier.x() else Modifier)` chains
  crash `SuspiciousModifierThenDetector` (NoClassDefFoundError). Use factory-style modifier
  extensions that take the condition instead — see `tutorialTarget(map-or-null, key)` and
  `tappableWhen(enabled) {}` in `GameScreen.kt`.

### Web target notes
- The Kotlin plugin's Node.js/Binaryen/Yarn download repositories are declared in
  `settings.gradle.kts` (both builds) because `PREFER_SETTINGS` ignores project-level repositories.
- Compose resource URLs are remapped relative (`configureWebResources` in `web/.../Main.kt`) so the
  app works from the GitHub Pages `/500/` subpath — keep that if resources ever 404 on Pages.
- `viewModel { GameViewModel() }` (explicit initializer) is required: the reflection-based default
  ViewModel factory is JVM-only and throws on wasm.
- Web-only known limits: page refresh loses an in-progress *local* game (`rememberSaveable` is
  memory-only on web) — *online* lobbies/games survive a reload via the session token in
  `sessionStorage` plus the server's lobby disconnect grace — and the first sound needs a prior
  user gesture (browser autoplay policy — the "New Game" tap qualifies).

## Releasing

- Release artifacts are signed when `KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`
  are set (env vars or gradle properties); absent ⇒ unsigned, which is correct for local builds and
  F-Droid. CI's tag-triggered release job (`v*` tags) exports them from repo secrets, builds
  `bundlePlayRelease` + `assembleFossRelease`, and publishes the FOSS APK to a GitHub release (the
  job needs `permissions: contents: write`). The same `v*` tags also trigger the `deploy-web` job,
  which publishes the wasm build to GitHub Pages (source is set to "GitHub Actions" in repo
  settings). `dependenciesInfo` is disabled — F-Droid rejects the
  Google-encrypted blob. Release stays un-minified until a release-QA pass justifies R8.
- A `v*` tag also **ships the online server**: `publish-server-image` builds the `:server` dist into
  a multi-arch image and pushes it to `ghcr.io/rotundtapir/500-server` (`:<version>` + `:latest`);
  `deploy-server` then SSHes to the VPS, pins `IMAGE_TAG`, and `docker compose pull && up` — no
  drain-and-wait since room snapshots (restart-safe rejoin); drain manually first only for a
  deploy that bumps `RoomSnapshot.CURRENT_VERSION` (see `docs/server-runbook.md`).
  It needs the `DEPLOY_SSH_KEY`/`DEPLOY_HOST`/`DEPLOY_PORT`/`DEPLOY_KNOWN_HOSTS` repo secrets (a
  dedicated CI-only ed25519 key) and the GHCR package to be public; see `docs/server-runbook.md`.
  Gotcha (now guarded): re-deploying the *same* version used to leave the box drained (`up -d`
  doesn't recreate an unchanged image, so the in-memory drain flag persisted); the deploy job now
  POSTs `/admin/undrain` unconditionally after `up`, a no-op on a real deploy. Manual undrain:
  `docker exec 500-server wget -qO- --post-data='' http://localhost:8080/admin/undrain`.
- fastlane metadata (`fastlane/metadata/android/en-US/`) is the store listing: `title.txt`,
  descriptions, `changelogs/<versionCode>.txt`, and `images/phoneScreenshots/`. Keep the changelog
  file in sync with `versionCode` bumps.

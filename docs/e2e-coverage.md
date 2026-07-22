# E2E coverage across platforms

Where each user-facing flow is exercised end-to-end, and which gaps are deliberate (issue #10).
Web e2e = Playwright over the production wasm build (`web/e2e/`, CI `build` job). Android E2E =
the connected instrumentation suite (`app/src/androidTest/`, CI `android-e2e` job, emulator +
host-run dev server). Server integration = JVM tests over real WebSockets (`server/src/test/`).

| Flow | Web e2e | Android E2E | Server integration |
| --- | --- | --- | --- |
| Boot / home screen | `boot.spec.ts` | `GameFlowTest.homeScreen…` | — |
| Local game vs bots (seed 42) | `game.spec.ts` | `GameFlowTest` (full hand, scoring, win dialog) | — |
| Interactive tutorial | `tutorial.spec.ts` (boot only) | `GameFlowTest` (full scripted hand) | — |
| Settings persistence | `settings.spec.ts` | covered by unit tests (`SettingsTest`) | — |
| Online: connect + create lobby | `online.spec.ts` | `OnlineFlowTest.test1…` | `OnlineServerTest` |
| Online: start game → play | `restart.spec.ts` (host-start to the human's bid turn + a bid) | `OnlineFlowTest.test1…` (to the human's bid turn) | full bot-backed games to completion |
| Online: reload / session resume | `online.spec.ts` (rejoin offer + own-link auto-rejoin) | n/a — the Android app doesn't reload; warm-start covered by unit tests | reconnect/resume + lobby disconnect grace |
| Online: server crash-restart mid-game (issue #20) | `restart.spec.ts` (own server, SIGKILL + relaunch on the same `DATA_DIR`; silent in-place resume, same hand, bid round-trips) | ✗ needs the host to bounce the process — manual (see #20) | `RestartRestoreTest` (snapshot restore + token reclaim) |
| Invite link: receive (deep link) | `online.spec.ts` (`?joinCode=` → Join screen) | `DeepLinkTest` (App Links VIEW intent) | — |
| Invite link: share (send) | ✗ web copies to clipboard — no share sheet to assert | `OnlineFlowTest.test2…` (ACTION_SEND chooser carries the URL) | — |
| Rematch, reconnect-mid-game, timeouts, abuse limits | — | — | `OnlineServerTest` / `SeatHostTest` |

## Intentional asymmetries

- **Web can't drive a *guest* past the lobby**: Compose-on-canvas exposes the ready `Switch` as a
  nameless button Playwright can't target, and after any dialog closes the wasm a11y mirror goes
  stale, so post-dialog assertions are impossible. The *host* path needs neither (clicking Start
  is the host's readiness), which is what lets `restart.spec.ts` reach live gameplay; guest-side
  gameplay is asserted on Android and in the server's JVM integration tests instead.
- **No Android analogue of a page reload**: the closest events (warm start, activity recreation)
  keep the ViewModel and are covered by unit tests; process-death resume is deliberately out of
  scope (the server's session TTL makes a much-later resume moot).
- **Share is Android-only as an e2e**: the web `LinkSharer` copies to the clipboard (asserted
  indirectly by the button's presence in `online.spec.ts`); the native share sheet only exists on
  Android.
- **Tutorial full-script run is Android-only**: it needs step-by-step card taps, which hit the
  same web canvas-click limitation; web asserts the tutorial boots.

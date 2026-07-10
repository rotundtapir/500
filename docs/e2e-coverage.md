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
| Online: start game → play | ✗ canvas limitation (see below) | `OnlineFlowTest.test1…` (to the human's bid turn) | full bot-backed games to completion |
| Online: reload / session resume | `online.spec.ts` (rejoin offer + own-link auto-rejoin) | n/a — the Android app doesn't reload; warm-start covered by unit tests | reconnect/resume + lobby disconnect grace |
| Invite link: receive (deep link) | `online.spec.ts` (`?joinCode=` → Join screen) | `DeepLinkTest` (App Links VIEW intent) | — |
| Invite link: share (send) | ✗ web copies to clipboard — no share sheet to assert | `OnlineFlowTest.test2…` (ACTION_SEND chooser carries the URL) | — |
| Rematch, reconnect-mid-game, timeouts, abuse limits | — | — | `OnlineServerTest` / `SeatHostTest` |

## Intentional asymmetries

- **Web stops at the lobby for online gameplay**: Compose-on-canvas exposes some controls (the
  ready `Switch`) as nameless buttons Playwright can't target, and after any dialog closes the
  wasm a11y mirror goes stale, so post-dialog assertions are impossible. Gameplay past the lobby
  is asserted on Android and in the server's JVM integration tests instead.
- **No Android analogue of a page reload**: the closest events (warm start, activity recreation)
  keep the ViewModel and are covered by unit tests; process-death resume is deliberately out of
  scope (the server's session TTL makes a much-later resume moot).
- **Share is Android-only as an e2e**: the web `LinkSharer` copies to the clipboard (asserted
  indirectly by the button's presence in `online.spec.ts`); the native share sheet only exists on
  Android.
- **Tutorial full-script run is Android-only**: it needs step-by-step card taps, which hit the
  same web canvas-click limitation; web asserts the tutorial boots.

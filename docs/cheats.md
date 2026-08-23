# The hidden cheats menu

Developer-style toys for local play, unlocked deliberately rather than shipped visible (#51).
Present in **release builds of both flavours**: behind the unlock gesture it costs nothing, and the
seed affordances are what turn "I had a bizarre deal" into a reproducible bug report — which only
helps if real users can reach them.

## Unlocking

Home screen → **About** → tap the **Version** row **7 times** (Android's developer-options gesture;
a countdown appears after the third tap). The flag persists via `SettingsRepository`
(`SettingsKeys.CHEATS_UNLOCKED`), so it survives a restart on Android and web alike, and the menu's
own **"Lock cheats again"** button reverses it.

Once unlocked, a **Cheats** section appears at the bottom of the settings dialog, on the home screen
and in a local game.

## Local games only — and why that is structural

The server is authoritative and redacts per seat, so none of these *can* work online. But the
guarantee is stronger than "the section is hidden when `online != null`":

- **See all hands** reads `GameViewModel.allHands`, which is derived from the local `GameState`.
  An online client never receives other hands, so that flow is simply empty there. Nothing was
  added to `PlayerView` — per CLAUDE.md, that field set is what goes over the wire, and widening it
  would leak every hand to every online client.
- **The seed** likewise lives only on the local `GameViewModel`. No `ServerMessage` carries a seed.

So a forgotten conditional cannot leak anything: there is nothing on the online path to leak.

### The seed is not diagnostic metadata

The engine is public and strictly seed-deterministic, so **a seed is equivalent to the whole game
state** — anyone holding it can run `FiveHundredRules.newGame(seed)` and reconstruct all four hands,
the kitty, and every future deal of the match. Showing it during an online game would be a genuine
competitive exploit. It is offline-only for that reason, not for tidiness.

The same fact is why `DEV_MODE=true` on a *server* is a confidentiality setting rather than a
convenience one (the lobby creator supplies the seed): see `docs/self-hosting.md`.

## What is in there

| Cheat | Notes |
|---|---|
| **Seed: `<n>`** + **Copy seed** | The current match's seed. Paste it into a bug report; anyone can replay that exact match. |
| **Show all hands** | Every other seat's cards, face up above the felt. Reuses the open-misère renderer, not its data path. |
| **Re-deal** | A fresh match on the next seed, same table shape/house rules. This *restarts* the match (score and hand history reset) rather than re-rolling the current hand: `rngSeed` evolves per deal, so re-dealing one hand in place would need the hand's entry seed kept in the engine. For "cycle deals until an interesting one shows up", a restart is what's wanted. |
| **Play a specific seed** | The inverse of Copy seed — together they are a complete reproduction loop for hand-specific bugs. |
| **Rigged deck (7+ / 8+ / 9+)** | Rejection-samples seeds until the local player's hand is worth a bid at that level, via `SeedSearch` in `:ai` (public so it is unit-testable there; the bot's estimator is `internal` to that module). Capped at `SeedSearch.DEFAULT_MAX_ATTEMPTS` and it yields between attempts, so the browser's single thread keeps painting; it reports "no seed found" rather than spinning. "8+" is a ladder comparison against 8♠ rather than a raw `ScoreSchedule.rank` index — the ladder is deliberately not point-ordered, and misère-only hands are excluded from the level filters for the same reason. |

## Deliberately not implemented yet

Listed in #51 and worth doing, but out of scope for the first cut: force a contract / skip the
auction, set the score, peek at the kitty, switch bot skill mid-match, fast-forward a hand, and
rewind a trick (the invasive one — the driver loop and the suspended bot `Player`s all drive
forward, so it likely needs its own issue).

## Testing

- `:ai/jvmTest` — `SeedSearchTest` covers the predicates, determinism ("first matching seed", or
  re-shooting a board drifts), the give-up path, and progress reporting.
- `:shared` unit tests — `CheatsTest` pins the unlock default/persistence/re-lock and the storage
  key, and asserts the invariant that matters: the local ViewModel holds every hand and the seed
  while the redacted `PlayerView` exposes neither.

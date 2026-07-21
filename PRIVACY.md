# Privacy Policy — 500

_Last updated: 2026-07-10_

## The free/libre build (F-Droid, GitHub releases)

This build contains **no ads, no analytics, and no third-party SDKs**. Played
offline, it does not collect, transmit, or share any personal data — all game
data (settings, statistics, saved games) stays on your device. If you choose to
play **online**, see "Online multiplayer" below.

## Online multiplayer (all builds — only if you play online)

Online play is entirely optional and off by default; offline play against bots
never touches the network. When you create or join an online game, the app
connects to a game server — the official one (`wss://500.29022617.xyz`) or any
server you configure yourself under **Settings → Online**.

- **What is sent:** the display name you choose for the game, your in-game moves,
  and canned emotes, plus build metadata for diagnostics — the app's version, its
  platform (Android or web), its distribution flavour (web/Play/F-Droid), and the
  short git commit it was built from. Build metadata never affects gameplay and is
  not tied to any identity. There is **no free-text chat** — only a fixed set of
  preset messages. No account, email, or password is required or collected.
- **Connection metadata:** the server sees your IP address (as any web server
  does). The official server uses it transiently to enforce anti-abuse limits
  (per-IP connection/rate caps) and, on repeated abuse, temporary IP bans via
  fail2ban. It is not used for tracking or advertising and is not shared.
- **Retention:** the official server keeps **all game state in memory only** —
  there is no database and nothing is persisted; a server restart discards
  everything. Operational logs (including connection records and abuse events)
  are short-lived: the official server's journal is capped in size (200 MB) and
  age (one month), after which entries are deleted automatically.
- **Self-hosting:** the server is open source and you can run your own and point
  the app at it, in which case the above applies to that operator instead. See
  [`docs/self-hosting.md`](docs/self-hosting.md).

## The Google Play build

The Google Play build displays ads using the **Google Mobile Ads SDK** and
offers an optional in-app purchase to remove them via **Google Play Billing**.

- **Advertising (Google AdMob).** To serve ads, Google may collect and process
  device and usage information, including advertising identifiers, approximate
  location (derived from IP), and app-interaction data. See Google's
  [Privacy & Terms](https://policies.google.com/privacy) and the
  [AdMob documentation](https://support.google.com/admob/answer/6128543).
  Users in the EEA/UK are shown a consent form (Google User Messaging Platform)
  before personalised ads are served.
- **Purchases (Google Play Billing).** If you buy "remove ads", the transaction
  is processed by Google Play. We receive only a confirmation that the purchase
  occurred; we do not receive or store your payment details. Afterwards the
  advertising SDK is no longer used.

Apart from the optional online-multiplayer server described above (which receives
only a chosen display name, game moves, and connection metadata — no account, no
ad data), the developers do not collect personal data directly in either build.

## Children

This app is not directed at children under 13; ads are configured accordingly.

## Contact

Questions? Contact rotund_tapir@protonmail.com.

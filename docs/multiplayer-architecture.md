# Online multiplayer: architecture analysis

**Status: analysis only — no decision made, nothing here applies to v0.1.**
Written 2026-07 to record the P2P-vs-hosted-server tradeoff discussion so it can be
revisited when online play is scheduled. The engine seams referenced below
(`GameRules.view`, `suspend Player.decide`, pure-JVM `engine`/`ai`) already exist and were
designed with this in mind; see `CLAUDE.md` → "The engine is a pure state machine".

## The two candidate architectures

**P2P (WebRTC):** phones connect directly over WebRTC data channels; one peer acts as the
authoritative host running the engine, the others send actions and receive views.

**Hosted server:** an authoritative server (pure JVM, reusing `cardkit-core` +
`engine` + `ai`) runs `GameDriver`; each phone holds one WebSocket, submits actions, and
receives its redacted `PlayerView`.

## Pros and cons

### P2P

Pros:

- Near-zero marginal hosting cost; gameplay has no central dependency.
- Games keep working even if the project goes dormant (as long as signaling exists).

Cons:

- **"No infrastructure" is a myth on mobile.** WebRTC still needs a *signaling server* to
  broker connections, *STUN* for address discovery, and a *TURN relay* for the pairings
  where NAT hole-punching fails (~10–20% generally; worse under mobile CGNAT). TURN relays
  the actual game traffic, so you run and pay for a server anyway — just a dumber one.
- **Host migration**: the authoritative peer's phone locks, drops signal, or quits
  mid-hand; recovering the game on another peer is genuinely hard.
- Large native `libwebrtc` dependency in the Android app, plus ICE/DTLS/SCTP complexity —
  all to move a few bytes per second for a turn-based game where latency is irrelevant.
- The anti-cheat hole below.

### Hosted server

Pros:

- One *outbound* WebSocket works through any NAT/CGNAT with zero traversal machinery.
- Authoritative hidden state (see anti-cheat).
- Trivial reconnection: "resend my current `PlayerView`".
- Natural home for lobbies, invite codes, bot fill-in for disconnected seats, replays,
  spectating.

Cons:

- Someone must run and maintain it; it is a single point of failure for online play.
- The operator can observe all games (mitigated by self-hosting, below).

## Anti-cheat

This is the decisive argument.

- **P2P:** someone must shuffle. The host peer holds the full `GameState` — every hand,
  the kitty, and `rngSeed` — so a trivially modded APK reads it all. That is fatal in 500
  specifically: misère and open misère hinge on hidden hands, and the kitty exchange is a
  huge information asymmetry. The academic escape hatch is "mental poker"
  (commutative-encryption shuffles, SRA-style), which needs no trusted party, but it is
  research-grade cryptography, interacts badly with 500's kitty, requires every player
  online for every crypto round, and is wildly disproportionate for a friendly card game.
- **Server:** hands never leave the server. Clients receive only `PlayerView` — which
  already omits other seats' hands, the stock, and `rngSeed` — and the server validates
  every submitted action via `legalActions`/`apply`. The engine was literally built for
  this: `GameRules.view(state, seat)` is the redaction seam.
- **Residual risk is identical in both models:** partners colluding out of band (a phone
  call) or a human consulting a solver cannot be prevented by any architecture.

## Server hardware requirements

Nearly nothing — a turn-based game over a pure-reducer engine is as cheap as servers get:

- **Bandwidth:** a `PlayerView` is a few KB of JSON. A hand is ~50–60 actions (bidding,
  kitty, 10 tricks × 4 plays), each fanning out 4 view updates → roughly 0.5–1 MB per hand
  spread over 5+ minutes ≈ **1–3 kbit/s per active game**. 1,000 concurrent games ≈ 4,000
  sockets and ~3 Mbit/s.
- **RAM:** `GameState` is tiny; per-connection cost (coroutine + socket buffers) dominates
  at tens of KB. A JVM with a 1 GB heap holds thousands of concurrent games.
- **CPU:** `apply()` is a pure function over small immutable data — effectively free. No
  tick loop; the server idles between human taps.

Concretely: a **1-vCPU / 1–2 GB VPS (~€5/month)** — or a Raspberry Pi — covers more
concurrent players than the game will realistically see. A well-provisioned TURN fallback
for the P2P option would cost about the same.

## Fit with the existing codebase

The hosted server wins, and it isn't close — the architecture was pre-shaped for it
(`engine` is a `kotlin("jvm")` module precisely so it can run server-side):

- Server = Ktor + WebSockets; one coroutine per room running the existing `GameDriver`.
- A `RemotePlayer : Player` whose `decide(view)` sends the view down the socket and
  suspends until an action returns is the exact mirror of today's `ChannelPlayer`
  (`cardkit-core`'s `Player` is already `suspend` for this reason).
- Bot fill-in for a disconnected seat = swap in `StrategyPlayer`; the driver already takes
  per-seat players.
- The client stops running the engine and becomes a thin renderer of the incoming
  `PlayerView` stream — the UI already renders from `PlayerView`, never `GameState`.
- kotlinx.serialization is already wired in `cardkit-core` and `engine` (`Card`, `Bid`,
  `Seat` are `@Serializable`); the remaining work is annotating
  `GameState`/`PlayerView`/`Action` and defining a small message envelope.

P2P would reuse the engine too (the host runs the driver) but stacks WebRTC, signaling,
and host migration on top — and keeps the cheat hole.

## Extensibility to future games

`GameRules<State, Action, View>` is generic, so a game-agnostic server core can host any
cardkit game: a game registers rules + serializers under a game-type id and rooms are
parameterised by it. Per-game load is ~kilobits, so adding Euchre or Hearts adds no
meaningful server load; one small box hosts the whole suite. Under P2P, every new game
re-inherits the host-knows-everything problem individually.

## Self-hosting

Counterintuitively, the hosted server is the *more* self-hostable option:

- Ship a GPL server as a single runnable JAR / Docker image; a family runs it on a Pi or a
  cheap VPS; the app grows a "server address" field in settings, with the official server
  as default (the Mindustry/Minetest model).
- Self-hosting the P2P alternative means operating signaling + STUN + TURN, which is
  harder to run than the game server itself.
- Self-hosting also answers the privacy con of a central server: don't trust the operator,
  *be* the operator.

## What lives in cardkit vs the 500 repo

- **`cardkit-core`** — unchanged. Stays pure; it is already the shared vocabulary
  (`GameRules`, `GameDriver`, `Player`, cards, dealing).
- **New `cardkit-server` (pure JVM)** — generic room/lobby management, seat assignment,
  join/rejoin with session tokens, `RemotePlayer`, the message envelope (game-type +
  protocol version + payload), heartbeats, bot-substitution hooks. Generic over
  `GameRules`; no Android, no game rules.
- **New `cardkit-net-client`** — the client half: WebSocket session, reconnect logic,
  exposing the same prompt/submit shape as `ChannelPlayer` so `GameViewModel` barely
  changes.
- **500 repo** — the 500-specific server binary (a thin `main()` composing
  `cardkit-server` + `engine` + `ai`), serializers for `GameState`/`PlayerView`/`Action`,
  500's matchmaking policy (4 seats, fixed partnerships), and the UI: lobby / invite-code
  screens and the online-game entry point. Each game ships its own server artifact this
  way; a multi-game binary stays possible later because registration is data.

## Current leaning (not a decision)

Authoritative hosted server: it is what the engine was shaped for, it is the only option
that keeps misère honest, and its infrastructure burden (one tiny self-hostable VPS) is
smaller than P2P's hidden signaling/TURN burden.

## Hosting costs (researched 2026-07)

Costs common to every scenario:

| Item | Cost | Notes |
|---|---|---|
| Domain | ~$10–15/yr | Needed everywhere — it is also the TLS identity. Cloudflare Registrar sells at cost (~$10.44/yr for .com). Free DDNS subdomains work but look bad as an app's hardcoded default server |
| TLS certificate | $0 | Let's Encrypt, or terminate at Cloudflare's edge |
| Monitoring | $0 | UptimeRobot / healthchecks.io free tiers |
| Backups | ~$0 | Game state is ephemeral (a crashed game is a lost hand, not lost data); config lives in git |
| DDoS fronting | $0 | Cloudflare free plan proxies WebSockets on all plans |
| Bandwidth | $0 marginal | ~1–3 kbit/s per game (above) is a rounding error against any VPS's included transfer |

Compute options:

- **Pi at home:** ~$8–15/yr electricity (3–6 W continuous) + the domain ⇒ **≈ $20–30/yr**
  (hardware owned). Requires dynamic DNS (free — cron job against the Cloudflare API) and,
  naively, a port forward. The forward is the real cost: the home IP is published in DNS
  (grudge-DoS hits the household's internet), the open port's compromise lands inside the
  LAN unless the Pi is VLAN-isolated, and ISP CGNAT may make forwarding impossible anyway.
  **Mitigation: Cloudflare Tunnel** — `cloudflared` on the Pi makes an outbound-only
  connection; free, WebSocket-capable, hides the home IP, works through CGNAT, no open
  port at all. (Tailscale Funnel is the alternative.) Residual con: household power or
  internet outages become the game's outages.
- **Cheap VPS:** e.g. Vultr $2.50/mo (IPv6-only) or $3.50/mo (dual-stack), both
  0.5 vCPU / 512 MB ⇒ **≈ $40–60/yr** with the domain. Hetzner CAX11 (2 ARM vCPU / 4 GB,
  ~€4.50/mo with IPv4) is dramatically more headroom per dollar at the same price point.

**Is 0.5 vCPU / 512 MB enough?** CPU: comfortably — the pure-reducer engine has no tick
loop and idles between human taps. RAM is the resource to tune: JVM 21 + Ktor/Netty with
the heap capped (`-Xmx192m`, SerialGC) runs ~150–250 MB RSS and hundreds of concurrent
games fit inside that heap; budget ~80–100 MB for minimal Debian + sshd, add zram/swap as
an OOM safety net and a systemd restart policy. GraalVM native-image (~50 MB RSS) is the
escape hatch; the ~$5–6 1 GB tier buys the right to never think about any of this.

**IPv6-only reachability is asymmetric.** Android on cellular is mostly fine (mobile
carriers are heavily IPv6; their 464XLAT helps clients reach IPv4-only *servers*, not the
reverse). Android on home Wi-Fi is the problem: the phone only has IPv6 if the home ISP
provides it, and Google's traffic only crossed 50% IPv6 in March 2026 — with AU/NZ (500's
likely audience) below the global average. A bare IPv6-only server refuses connections
from a third to half of players, with a "works on mobile data, fails on my Wi-Fi" failure
mode. Workaround: a proxied AAAA record behind free Cloudflare (dual-stack edge, origin
reached over IPv6; WebSockets supported on the free plan; client heartbeats must stay
under Cloudflare's ~100 s proxy idle timeout — heartbeats are already in the
`cardkit-server` design). **Recommendation:** the $1/mo saving is not worth making basic
reachability depend on a third-party proxy — take the dual-stack plan, and let Cloudflare
be optional armor rather than load-bearing.

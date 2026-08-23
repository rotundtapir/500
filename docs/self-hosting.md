<!-- SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception -->
# Self-hosting the 500 server

The 500 game server is GPL and ships as a small container image
(`ghcr.io/rotundtapir/500-server`). You can run your own — for a family game night on a Raspberry Pi,
for a private group, or to develop/test the app against a local server. Point any client at your
server via **Settings → Online → Game server**.

Live state is in memory; there is no database. Set **`DATA_DIR`** to a writable directory (the
bundled compose file mounts a volume at `/data`) and the server also snapshots each room to disk,
so in-progress games **survive a restart** — players rejoin their seats automatically with their
session tokens. Snapshots are transient (deleted when a room ends or idles out) and are the only
thing on disk; there is still nothing worth backing up. Without `DATA_DIR`, a restart drops every
in-progress game (a lost hand, not lost data).

## Quick start — LAN, no TLS

```bash
docker run --rm -p 8080:8080 \
  -e ALLOWED_ORIGINS='*' -e DEV_MODE=false \
  ghcr.io/rotundtapir/500-server:latest
```

Point native/desktop clients at `ws://<host-ip>:8080`.

> **Only expose port 8080 on a trusted LAN.** The container serves `/admin/drain`, `/admin/undrain`,
> and `/metrics` with no authentication — the production setup keeps them private by putting Caddy in
> front (it 403s `/admin*` and `/metrics`) and publishing *no* host port for the server itself. If you
> bind 8080 to a public interface, anyone can drain your server or read its metrics. Bind it to
> `127.0.0.1:8080` or a LAN-only address, or front it with the Caddy setup below.

> The web client hosted on GitHub Pages is served over HTTPS and browsers block plain `ws://` to a
> non-localhost host (mixed content). Plain `ws://` therefore works for the Android app and for a
> locally-served web build, but not for the public Pages site. For a browser over the internet you
> need TLS — see below.

## Public server with your own domain (TLS via Caddy)

1. Copy `server/deploy/docker-compose.yml` and `server/deploy/Caddyfile` to your host.
2. Create a `.env` next to them:
   ```
   IMAGE_TAG=<a released version, e.g. 0.3.0>
   SERVER_DOMAIN=play.example.com
   ACME_EMAIL=you@example.com
   ALLOWED_ORIGINS=https://your-web-origin
   ```
3. Point `SERVER_DOMAIN`'s DNS at the host (A/AAAA record).
4. `docker compose up -d`.

Caddy obtains and renews a Let's Encrypt certificate automatically. While first testing, uncomment
the staging `acme_ca` line in the `Caddyfile` so a misconfiguration can't burn the production
certificate rate limit; comment it out and `docker compose restart caddy` once it works.

## Configuration (environment variables)

| Variable | Default | Meaning |
| --- | --- | --- |
| `PORT` | `8080` | Listen port (inside the container). |
| `ALLOWED_ORIGINS` | `https://rotundtapir.github.io` | Comma-separated allowed WebSocket origins (CSWSH defence). Set `*` to allow any origin (LAN/self-host). |
| `TRUST_PROXY` | `false` | Trust `X-Forwarded-For` — set `true` only behind a reverse proxy like Caddy. |
| `MIN_APP_VERSION` | `0.3.0` | Oldest app version accepted; older clients are told to update. |
| `MAX_CONNECTIONS_PER_IP` | `8` | Per-IP connection cap. |
| `MSG_RATE_PER_SEC` / `MSG_BURST` | `10` / `20` | Per-socket message rate limit. |
| `LOBBIES_PER_IP_PER_10MIN` | `5` | Lobby-creation throttle. |
| `MAX_ROOMS` | `500` | Server-wide room cap. |
| `LOBBY_GRACE_MILLIS` | `900000` (15 min) | How long a lobby/post-game seat is held for its owner after a bare socket drop (a page reload, a network blip) before the room is disbanded (creator) or the seat freed (guest). |
| `GAME_GRACE_MILLIS` | `180000` (3 min) | How long a PLAYING seat is held for its owner after a socket drop before the bot takes over (an app-switch on Android drops the socket within seconds). The current turn keeps waiting during the grace; `0` restores instant bot substitution. |
| `DATA_DIR` | *(unset)* | Directory for transient per-room snapshots. Set it (the bundled compose file uses `/data` on a volume) and in-progress games survive a server restart — players rejoin their seats automatically. Unset ⇒ in-memory only; a restart drops every game. |
| `DEV_MODE` | `false` | Relaxes IP/rate limits and honours a client-supplied game seed. **Local testing only — this is a confidentiality flag, not just a convenience one.** The engine is public and strictly seed-deterministic, so whoever creates a lobby on such a server knows the seed and can reconstruct every player's hand and every future deal of that match. Never set it on a server other people play on. |

## Resource needs

Runs comfortably in **512 MB** with a capped heap (`JAVA_OPTS=-Xms64m -Xmx256m`), and 1 vCPU covers
far more concurrent tables than a hobby server will see — the engine is a pure reducer with no tick
loop, idling between turns.

## Origin posture

WebSockets are not subject to CORS, so the server checks the `Origin` header against
`ALLOWED_ORIGINS` itself (the anti-CSWSH measure). Non-browser clients send no `Origin` and are
always allowed. For LAN play from a browser you control, set `ALLOWED_ORIGINS=*`.

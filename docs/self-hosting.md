<!-- SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception -->
# Self-hosting the 500 server

The 500 game server is GPL and ships as a small container image
(`ghcr.io/rotundtapir/500-server`). You can run your own — for a family game night on a Raspberry Pi,
for a private group, or to develop/test the app against a local server. Point any client at your
server via **Settings → Online → Game server**.

The server keeps **all state in memory**: a restart drops every in-progress game (a lost hand, not
lost data). There is no database and nothing to back up.

## Quick start — LAN, no TLS

```bash
docker run --rm -p 8080:8080 \
  -e ALLOWED_ORIGINS='*' -e DEV_MODE=false \
  ghcr.io/rotundtapir/500-server:latest
```

Point native/desktop clients at `ws://<host-ip>:8080`.

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
| `DEV_MODE` | `false` | Relaxes IP/rate limits and honours a client-supplied game seed. **Local testing only.** |

## Resource needs

Runs comfortably in **512 MB** with a capped heap (`JAVA_OPTS=-Xms64m -Xmx256m`), and 1 vCPU covers
far more concurrent tables than a hobby server will see — the engine is a pure reducer with no tick
loop, idling between turns.

## Origin posture

WebSockets are not subject to CORS, so the server checks the `Origin` header against
`ALLOWED_ORIGINS` itself (the anti-CSWSH measure). Non-browser clients send no `Origin` and are
always allowed. For LAN play from a browser you control, set `ALLOWED_ORIGINS=*`.

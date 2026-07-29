<!-- SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception -->
# 500 server runbook (official VPS)

The official server runs on a netcup VPS (1 vCPU / 1 GB, Debian 13) behind Caddy, in Docker Compose
at `/opt/500-server`. Live state is in-memory, with transient per-room snapshots in the
`server_data` volume (`DATA_DIR=/data`) so in-flight games survive restarts and deploys — snapshots
delete themselves when rooms end, so there is still nothing to back up.

## First-time bootstrap

The box is rebuildable from scratch: reinstall Debian 13, then from a checkout of this repo:

```bash
cd server/deploy
sudo ./bootstrap.sh          # installs Docker, firewall, fail2ban, swap, drain-reboot timer, …
```

Then complete the manual steps it prints: add the CI deploy key, set DNS, make the GHCR package
public, add an UptimeRobot monitor. See [DNS setup](#dns-porkbun) below.

## Deploys

CI deploys automatically on a `v*` tag (`.github/workflows/ci.yml` → `deploy-server`): it pins the
new `IMAGE_TAG` in `/opt/500-server/.env`, runs `docker compose pull && up -d --wait`, undrains
unconditionally, and verifies `https://500.29022617.xyz/health`.

There is deliberately **no drain-and-wait** in the deploy: room snapshots (`DATA_DIR=/data`, the
`server_data` volume) mean the new process restores every room at boot and clients reconnect into
their seats automatically — while draining blocked all *new* lobbies for up to 15 minutes per
release. The one deploy that should still drain first is one that **bumps the snapshot schema
version** (`RoomSnapshot.CURRENT_VERSION` — a restore-incompatible engine/state change), so games
finish on the old code instead of being dropped at restore. Do that manually before pushing the tag:

```bash
docker exec 500-server wget -qO- --post-data='' http://localhost:8080/admin/drain
watch -n 30 'docker exec 500-server wget -qO- http://localhost:8080/health'   # until activeGames:0
```

(the deploy's unconditional undrain then reopens the box; games running past your patience get
dropped at restore, which is the pre-snapshot status quo).

Required repo secrets: `DEPLOY_SSH_KEY` (a dedicated ed25519 private key; its public half in
`/root/.ssh/authorized_keys`), `DEPLOY_HOST` (the IP — not DNS, so deploys don't depend on it),
`DEPLOY_PORT`, `DEPLOY_KNOWN_HOSTS` (`ssh-keyscan -p <port> <ip>`).

## Roll back a bad deploy

Every release image is an immutable semver tag and the box holds no durable state (room snapshots
are transient; they carry a schema version and a server that doesn't match it drops that room at
boot rather than guessing), so rollback is just re-pinning the previous tag:

```bash
ssh -p 51753 root@193.30.120.150
cd /opt/500-server
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=<previous-version>/' .env
docker compose up -d
```

In-flight games are restored from their snapshots on the way back up. If the *snapshots themselves*
are suspected bad (e.g. the bad deploy wrote states the old code can't restore), clear them first:
`docker compose down && docker volume rm 500-server_server_data` — that ends in-flight games, the
pre-snapshot behaviour.

## Inspecting logs

```bash
journalctl CONTAINER_NAME=500-server -f      # app log (incl. structured ABUSE lines)
docker compose -f /opt/500-server/docker-compose.yml logs -f caddy   # TLS/ACME
fail2ban-client status 500-server           # current bans (game server jail)
fail2ban-client status sshd                  # ssh jail
```

The app log also carries INFO telemetry, one line each:
- `connect id=.. ip=.. platform=.. flavor=.. version=.. commit=.. resume=..` — every accepted client,
  with its build (platform android/web, distribution web/play/foss, app version, git commit).
- `lobby created code=.. game=.. players=.. teams=..` — every new lobby.
- `join code=.. seat=.. name=.. creator=.. conn=..` — every player seated (correlate `conn=` with a
  `connect` line's `id=` for that player's build).

Useful filters: `journalctl CONTAINER_NAME=500-server -g '^.*(connect|lobby created|join) ' --no-pager`,
or count clients by flavour: `journalctl CONTAINER_NAME=500-server -g 'connect ' -o cat | grep -oP 'flavor=\K\S+' | sort | uniq -c`.

**Log volume / disk:** both containers use the journald log driver, so all of this lands in the
systemd journal. It is capped in `/etc/systemd/journald.conf.d/size.conf` (installed by
`bootstrap.sh`): `SystemMaxUse=200M`, `SystemKeepFree=1G`, `SystemMaxFileSize=50M`,
`MaxRetentionSec=1month`. journald auto-vacuums to honour these — there is no logrotate for it (it
manages text files, not the binary journal). Check with `journalctl --disk-usage`.

## Triage

```bash
docker compose -f /opt/500-server/docker-compose.yml ps
docker stats --no-stream
free -m
docker exec 500-server wget -qO- http://localhost:8080/metrics   # Prometheus-format counters
docker exec 500-server wget -qO- http://localhost:8080/health
```

Drain / undrain manually (e.g. before a maintenance restart):

```bash
docker exec 500-server wget -qO- --post-data='' http://localhost:8080/admin/drain
docker exec 500-server wget -qO- --post-data='' http://localhost:8080/admin/undrain
```

## Security-update reboots

`unattended-upgrades` applies security patches nightly but does **not** auto-reboot. A systemd timer
(`500-reboot-if-idle.timer`, ~18:00 UTC / ~04:00 AEST) checks for `/run/reboot-required` and, if
present, drains the server and reboots once games have finished (cap 1 h). Services return on boot
(`restart: unless-stopped` + `systemctl enable docker`).

## Footguns

- **Never add `ports:` to the `server` service** in the compose file. Docker-published ports bypass
  the host's nftables INPUT chain, so that would expose the game server to the internet regardless
  of the firewall. Only Caddy publishes ports.
- fail2ban bans for proxied traffic must use the `DOCKER-USER` chain (already configured); the
  default INPUT-chain ban does nothing against forwarded packets.
- **fail2ban bans are IPv4-only** as configured (`iptables-multiport[chain=DOCKER-USER]`). Docker
  doesn't maintain `DOCKER-USER` for IPv6 by default, so an abusive IPv6 client is logged but not
  banned. If you publish an AAAA record and see IPv6 abuse, either drop the AAAA or add an
  `ip6tables`-based ban action — v6 banning is not yet wired up.
- 1 GB RAM is tight (~700–850 MB steady). The 500 server is now capped at `-Xmx144m` /
  `mem_limit: 288m` rather than 256m/512m, to leave room for a second game server (euchre) on the
  same box. If memory gets tight, take both games down a step together — they are sized as a pair,
  and the numbers live in each repo's `server/deploy/docker-compose.yml`.
- **A `.corrupt` snapshot file is not necessarily a disk problem.** Until the cardkit-server
  adoption, `flushSync` and the snapshot writer could write the same room's temp file concurrently
  and publish the interleaved result, which was then quarantined as `.corrupt` and cost that room
  its game at the next boot. Any quarantine file older than that deploy is more likely that race
  than bad hardware. Also fixed at the same time: `flushSync` could throw out of the shutdown
  handler when exactly one room was pending, abandoning every snapshot still queued — so a deploy
  could drop games it was supposed to preserve.

## Hosting a second game on this box

The Caddy in 500's compose project is the shared edge for every game. Its `Caddyfile` no longer
contains a vhost: it does `import /etc/caddy/sites/*.caddy`, and **each game's deploy job owns
exactly one file in that directory** (500 writes `500.caddy`). That is what lets two repos add
hostnames and deploy independently without ever rewriting each other's config.

A game's own server container lives in its own compose project and joins the external `edge`
network, which is how Caddy reaches it across projects.

One-time migration on the box, before another game deploys for the first time (idempotent, and the
next 500 tag deploy will simply re-sync the same files):

```bash
mkdir -p /opt/caddy-sites
docker network create edge          # shared by Caddy and each game's server
cd /opt/500-server
# copy the updated docker-compose.yml, Caddyfile and sites/500.caddy from the repo, then:
cp <repo>/server/deploy/sites/500.caddy /opt/caddy-sites/
docker compose up -d --wait
docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile
curl -fsS https://500.29022617.xyz/health   # confirm 500 is still served
```

Removing a game later is `rm /opt/caddy-sites/<game>.caddy`, a `caddy reload`, and
`docker compose -f /opt/<game>-server/docker-compose.yml down`. 500 is unaffected either way.

## DNS (porkbun)

1. porkbun → Domain Management → `29022617.xyz` → DNS Records (leave the apex records alone).
2. Add **A**: host `500`, answer `193.30.120.150`, TTL 600.
3. On the VPS run `ip -6 addr show scope global`; if a global IPv6 exists, add a matching **AAAA**
   record. If not, skip it — a broken AAAA breaks Let's Encrypt validation.
4. Verify before first bring-up: `dig +short 500.29022617.xyz @1.1.1.1` → the IP.

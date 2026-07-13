#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
#
# One-time (idempotent) host setup for the 500 game server on a fresh Debian 13 (trixie) VPS.
# Run once as root, from this directory:   ./bootstrap.sh
# It installs Docker + Caddy-via-compose prerequisites, a firewall, fail2ban, unattended security
# upgrades, swap, and a drain-before-reboot timer. It does NOT deploy the app — CI does that
# (docker compose pull && up). See docs/server-runbook.md.
#
# Re-running is supported for config tweaks, but note: re-applying the nftables ruleset and (if the
# Docker daemon config changed) restarting Docker will briefly disrupt networking and END any live
# games. Drain first (docs/server-runbook.md → Drain / undrain) before re-running on a live box.

set -euo pipefail

if [[ $EUID -ne 0 ]]; then
	echo "Run as root." >&2
	exit 1
fi

HERE="$(cd "$(dirname "$0")" && pwd)"
SSH_PORT="${SSH_PORT:-51753}"
SERVER_DOMAIN="${SERVER_DOMAIN:-500.29022617.xyz}"
APP_DIR=/opt/500-server

echo "==> Installing packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y \
	docker.io docker-compose \
	nftables fail2ban unattended-upgrades zram-tools \
	rsync curl ca-certificates

echo "==> Hardening SSH (listen on the custom port; go key-only once a key is installed)"
install -d -m 755 /etc/ssh/sshd_config.d
# The firewall below only opens ${SSH_PORT}, so sshd MUST listen there (a fresh Debian defaults to
# 22, which would then be unreachable). And we only disable password auth once /root/.ssh/
# authorized_keys exists — otherwise a fresh box with no key installed would lock everyone out.
{
	echo "Port ${SSH_PORT}"
	echo "X11Forwarding no"
	echo "KbdInteractiveAuthentication no"
	if [[ -s /root/.ssh/authorized_keys ]]; then
		echo "PermitRootLogin prohibit-password"
		echo "PasswordAuthentication no"
	else
		echo "# No /root/.ssh/authorized_keys yet: password auth left ON so you aren't locked out."
		echo "# Add the deploy key, then re-run bootstrap (or edit this file) to switch to key-only."
		echo "PermitRootLogin yes"
		echo "PasswordAuthentication yes"
	fi
} > /etc/ssh/sshd_config.d/50-hardening.conf
if [[ ! -s /root/.ssh/authorized_keys ]]; then
	echo "    WARNING: no SSH key installed — leaving password auth ON. Add /root/.ssh/authorized_keys." >&2
fi
systemctl reload ssh || systemctl reload sshd || true

echo "==> Firewall (nftables): drop input except lo/established/icmp and ssh/http/https"
cat > /etc/nftables.conf <<EOF
#!/usr/sbin/nft -f
flush ruleset

table inet filter {
	chain input {
		type filter hook input priority 0; policy drop;
		iif lo accept
		ct state established,related accept
		ct state invalid drop
		meta l4proto { icmp, ipv6-icmp } accept
		tcp dport ${SSH_PORT} accept
		tcp dport { 80, 443 } accept
		udp dport 443 accept
	}
}
# Note: Docker manages the forward/DOCKER chains via iptables-nft; do not add a forward chain here.
EOF
systemctl enable --now nftables
nft -f /etc/nftables.conf

echo "==> Docker: journald log driver (so fail2ban and docker logs both work)"
install -d -m 755 /etc/docker
DOCKER_CFG=/etc/docker/daemon.json
NEW_DOCKER_CFG='{ "log-driver": "journald" }'
docker_changed=0
if [[ ! -f $DOCKER_CFG ]] || [[ "$(cat "$DOCKER_CFG")" != "$NEW_DOCKER_CFG" ]]; then
	printf '%s\n' "$NEW_DOCKER_CFG" > "$DOCKER_CFG"
	docker_changed=1
fi
mkdir -p /etc/systemd/journald.conf.d
# Both containers log via the journald driver, so bounding the journal is what stops logs — including
# the per-connection/lobby/join telemetry — from filling the disk. journald auto-vacuums to these
# limits; logrotate is not involved (it manages text files, not the binary journal).
cat > /etc/systemd/journald.conf.d/size.conf <<'EOF'
[Journal]
SystemMaxUse=200M
SystemKeepFree=1G
SystemMaxFileSize=50M
MaxRetentionSec=1month
EOF
systemctl restart systemd-journald
systemctl enable --now docker
# Only restart Docker when its config actually changed — a blind restart on every re-run would kill
# every running container (and any live game) for no reason.
if [[ $docker_changed -eq 1 ]]; then
	echo "    docker daemon.json changed — restarting Docker"
	systemctl restart docker
fi

echo "==> fail2ban: sshd (custom port) + 500-server (journald)"
cp "$HERE/fail2ban/500-server.conf" /etc/fail2ban/filter.d/500-server.conf
cat > /etc/fail2ban/jail.d/500.local <<EOF
[sshd]
enabled  = true
port     = ${SSH_PORT}
backend  = systemd

[500-server]
enabled      = true
backend      = systemd
journalmatch = CONTAINER_NAME=500-server
filter       = 500-server
port         = http,https
# Bans against proxied traffic must land in DOCKER-USER; the default INPUT ban misses forwarded packets.
banaction    = iptables-multiport[chain="DOCKER-USER"]
maxretry     = 10
findtime     = 5m
bantime      = 1h
EOF
systemctl enable --now fail2ban
systemctl restart fail2ban

echo "==> Unattended security upgrades (no blind auto-reboot; see the drain timer below)"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF
cat > /etc/apt/apt.conf.d/51local-unattended <<'EOF'
Unattended-Upgrade::Automatic-Reboot "false";
EOF
systemctl enable --now unattended-upgrades

echo "==> Swap: zram (50%) + a 1 GiB disk swapfile backstop for the 1 GB box"
sed -i 's/^#\?PERCENT=.*/PERCENT=50/' /etc/default/zramswap 2>/dev/null || echo "PERCENT=50" > /etc/default/zramswap
systemctl enable --now zramswap.service || systemctl restart zramswap.service || true
if [[ ! -f /swapfile ]]; then
	fallocate -l 1G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=1024
	chmod 600 /swapfile
	mkswap /swapfile
	swapon /swapfile
	grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw,pri=-2 0 0' >> /etc/fstab
fi
echo 'vm.swappiness=100' > /etc/sysctl.d/99-swappiness.conf
sysctl -p /etc/sysctl.d/99-swappiness.conf || true

echo "==> Drain-before-reboot timer (applies pending kernel updates at a quiet hour, gracefully)"
install -m 755 "$HERE/reboot-if-idle.sh" /usr/local/sbin/500-reboot-if-idle.sh
cat > /etc/systemd/system/500-reboot-if-idle.service <<'EOF'
[Unit]
Description=Drain the 500 server and reboot if a kernel update is pending

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/500-reboot-if-idle.sh
EOF
cat > /etc/systemd/system/500-reboot-if-idle.timer <<'EOF'
[Unit]
Description=Nightly check for a pending reboot (18:00 UTC ~ 04:00 AEST)

[Timer]
OnCalendar=*-*-* 18:00:00 UTC
Persistent=true

[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now 500-reboot-if-idle.timer

echo "==> App directory + .env skeleton"
install -d -m 755 "$APP_DIR"
if [[ ! -f "$APP_DIR/.env" ]]; then
	cat > "$APP_DIR/.env" <<'EOF'
# Written by CI on each deploy (IMAGE_TAG); edit the rest as needed.
IMAGE_TAG=latest
SERVER_DOMAIN=500.29022617.xyz
ACME_EMAIL=rotund_tapir@protonmail.com
ALLOWED_ORIGINS=https://rotundtapir.github.io
MIN_APP_VERSION=0.3.0
EOF
fi

cat <<EOF

==> Bootstrap complete. Remaining manual steps:
  1. Add the CI deploy public key to /root/.ssh/authorized_keys (secret DEPLOY_SSH_KEY's pair).
  2. Point DNS: porkbun A record  500 -> $(curl -s https://api.ipify.org || echo '<this host IP>')
     (and an AAAA if 'ip -6 addr show scope global' shows a global address).
  3. Make the GHCR package ghcr.io/rotundtapir/500-server public (one-time, GitHub package settings).
  4. Tag a release (vX.Y.Z) to trigger the CI build/deploy, or run 'docker compose pull && up -d' here.
  5. Add an UptimeRobot HTTPS monitor on https://$SERVER_DOMAIN/health.
EOF

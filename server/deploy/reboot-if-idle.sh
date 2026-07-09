#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
#
# Installed as /usr/local/sbin/500-reboot-if-idle.sh and run nightly by a systemd timer. If a kernel
# security update has left /run/reboot-required, drain the game server (stop new lobbies), wait a
# bounded time for active games to finish, then reboot. Applies security reboots without cutting a
# game short mid-hand. A plain deploy uses the same /admin/drain endpoint (see the CI deploy job).

set -euo pipefail

[[ -f /run/reboot-required ]] || { echo "No reboot pending."; exit 0; }

CONTAINER=500-server
MAX_WAIT_SECONDS=3600   # 1 hour cap, then reboot regardless
POLL_SECONDS=60

exec_in() { docker exec "$CONTAINER" "$@"; }

active_games() {
	# /health is JSON: {"status":"ok","rooms":N,"activeGames":M,"draining":bool}
	exec_in wget -qO- http://localhost:8080/health 2>/dev/null \
		| grep -o '"activeGames":[0-9]*' | grep -o '[0-9]*' || echo 0
}

if docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
	echo "Draining $CONTAINER before reboot"
	exec_in wget -qO- --post-data='' http://localhost:8080/admin/drain >/dev/null 2>&1 || true

	waited=0
	while (( waited < MAX_WAIT_SECONDS )); do
		games=$(active_games)
		echo "active games: $games (waited ${waited}s)"
		[[ "$games" == "0" ]] && break
		sleep "$POLL_SECONDS"
		waited=$(( waited + POLL_SECONDS ))
	done
fi

echo "Rebooting to apply pending updates."
systemctl reboot

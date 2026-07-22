// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
import { test, expect, Page } from '@playwright/test';
import { ChildProcess, spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { awaitAppBoot, clickByRole } from './helpers';

// Issue #20: the one full-stack restart test — a real browser client with a real server SIGKILLed
// and relaunched underneath it mid-game. Both seams are already pinned in isolation (server:
// RestartRestoreTest; client: OnlineClientTest's reconnect loop and silent same-room resume); this
// spec crosses them: snapshot restore + token rebind on one side, backoff/Hello/resume on the other.
//
// It deliberately does NOT use the shared webServer game server from playwright.config (killing
// that would take every other online test down with it). It spawns its own :server:installDist
// process on a side port with a private DATA_DIR, so the restart disturbs nothing else.
const PORT = 8790;
const SERVER_BIN = path.resolve(__dirname, '../../../server/build/install/server/bin/server');
const FIXTURE = `/500/?serverUrl=ws://localhost:${PORT}&playerName=Tester&animationSpeed=OFF&soundVolume=0`;

/** Card labels as PlayingCard exposes them (contentDescription = card.label). */
const CARD_NAME = /^(?:[2-9]|1[0-3]|J|Q|K|A)[♠♥♦♣]$|^Joker$/;

let dataDir: string;
let server: ChildProcess | undefined;

function startServer(): ChildProcess {
  const proc = spawn(SERVER_BIN, [], {
    env: {
      ...process.env,
      PORT: String(PORT),
      DEV_MODE: 'true',
      ALLOWED_ORIGINS: '*',
      MIN_APP_VERSION: '0.0.0',
      DATA_DIR: dataDir,
    },
    stdio: 'ignore',
  });
  proc.on('error', (err) => {
    throw new Error(`failed to spawn ${SERVER_BIN}: ${err}`);
  });
  return proc;
}

async function waitHealthy(timeoutMs = 60_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://localhost:${PORT}/health`);
      if (res.ok) return;
    } catch {
      // not up yet
    }
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`server on :${PORT} not healthy within ${timeoutMs}ms`);
}

/**
 * Wait until the room snapshot under DATA_DIR exists and has stopped changing. RoomPersistence
 * writes through a conflated async writer, so a SIGKILL straight after the last action could race
 * the write; the board is quiescent when this is called (it's the human's turn), so "file present
 * and stable" means the snapshot covers the state we assert on after the restart.
 */
async function waitForStableSnapshot(timeoutMs = 15_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < deadline) {
    const rooms = fs.readdirSync(dataDir).filter((f) => f.endsWith('.json'));
    if (rooms.length > 0) {
      const current = rooms
        .map((f) => {
          const s = fs.statSync(path.join(dataDir, f));
          return `${f}:${s.size}:${s.mtimeMs}`;
        })
        .join(',');
      if (current === last) return;
      last = current;
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`no stable room snapshot in ${dataDir} within ${timeoutMs}ms`);
}

/** The player's hand: card-labelled imgs (idle cards; playable ones would be buttons). Sorted. */
async function handCards(page: Page): Promise<string[]> {
  const labels: string[] = [];
  for (const img of await page.getByRole('img').all()) {
    const name =
      (await img.getAttribute('aria-label')) ??
      (await img.getAttribute('alt')) ??
      (await img.textContent())?.trim() ??
      '';
    if (CARD_NAME.test(name)) labels.push(name);
  }
  return labels.sort();
}

test.beforeAll(async () => {
  dataDir = fs.mkdtempSync(path.join(os.tmpdir(), '500-restart-e2e-'));
  server = startServer();
  await waitHealthy();
});

test.afterAll(() => {
  server?.kill('SIGKILL');
  fs.rmSync(dataDir, { recursive: true, force: true });
});

// The whole journey in one test: several minutes of budget because it boots the wasm app, plays
// to the human's bid turn, and rides a JVM restart plus the client's reconnect backoff (≤ 8 s).
// No console-error assertion, deliberately: the killed WebSocket and the reconnect attempts that
// fail while the server is down log errors that are exactly the point of the test.
test('an online game survives a server SIGKILL and restart under a live client', async ({ page }) => {
  test.setTimeout(240_000);

  await page.goto(FIXTURE);
  await awaitAppBoot(page);

  // Host a lobby (same preamble as online.spec.ts, but against our private server).
  await clickByRole(page, 'button', 'Play with friends');
  await clickByRole(page, 'button', 'Create a game');
  await clickByRole(page, 'button', /^Create$/);
  await expect(page.getByText(/^[2-9A-HJ-NP-Z]{4}$/).first()).toBeVisible({ timeout: 15_000 });

  // The host needs no ready toggle — Start is their readiness — and empty seats become bots.
  await clickByRole(page, 'button', /^Start/);

  // Play to the human's bid turn: a stable state, since the game cannot advance without us. The
  // server deals from a random seed, so assertions key on structure (10 cards), not exact cards.
  await expect(page.getByText('Your bid:')).toBeVisible({ timeout: 60_000 });
  const handBefore = await handCards(page);
  expect(handBefore).toHaveLength(10);

  // Crash-restart the server: SIGKILL (not graceful stop — that path flushes; the crash must be
  // survivable from the already-written snapshot alone), then a fresh process on the same port
  // and DATA_DIR, exactly like a deploy or OOM-kill under a live table.
  await waitForStableSnapshot();
  server!.kill('SIGKILL');

  // The client notices the dead socket and shows the reconnect banner (1.5 s grace before it).
  await expect(page.getByText('Reconnecting…')).toBeVisible({ timeout: 15_000 });

  server = startServer();
  await waitHealthy();

  // Reconnect is silent and in place: the backoff-loop Hello presents the session token, the
  // restored server rebinds it to the seat, and the banner clears — with no rejoin prompt,
  // because the room is the one already on screen.
  await expect(page.getByText('Reconnecting…')).toBeHidden({ timeout: 30_000 });
  await expect(page.getByText('Rejoin your game?')).not.toBeVisible();
  await expect(page.getByText(/You're still in game/)).not.toBeVisible();

  // Same game, same turn, same hand — the snapshot restored the exact mid-auction state.
  await expect(page.getByText('Your bid:')).toBeVisible({ timeout: 30_000 });
  expect(await handCards(page)).toEqual(handBefore);

  // And the game is playable onward: a bid round-trips through the restarted server (the view
  // only changes when the server broadcasts it back — there is no optimistic local update).
  // Usually the auction moves on past us ("You — passed" / "Waiting for X…"); in the all-pass
  // corner a redeal replaces our hand instead, so a changed 10-card hand also counts as progress.
  await clickByRole(page, 'button', /^Pass$/);
  await expect
    .poll(
      async () => {
        if (await page.getByText('You — passed').isVisible()) return true;
        if (await page.getByText(/^Waiting for .+…$/).first().isVisible()) return true;
        const hand = await handCards(page);
        return hand.length === 10 && hand.join() !== handBefore.join();
      },
      { timeout: 30_000 },
    )
    .toBe(true);
});

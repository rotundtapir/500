// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
import { test, expect } from '@playwright/test';
import { awaitAppBoot, clickByRole, collectErrors } from './helpers';

// End-to-end online smoke: the browser (wasm) Ktor client talks to a real local game server over a
// WebSocket. This is the only test that exercises the browser client against the server, and it
// covers the whole integration seam that unit tests can only fake: connect, the Hello/Welcome
// handshake, CreateLobby, and rendering the server's LobbyState. Player name and server URL are
// injected as URL params so we avoid typing into the Compose canvas.
//
// Deliberately stops at lobby creation. Readying and starting need a click on the ready Switch,
// which Compose exposes on the canvas as a nameless button that Playwright can't reliably target
// (the known canvas-interaction limitation). The start→bot-fill→gameplay path is instead covered by
// the server's JVM integration tests (full bot-backed games over real WebSockets) and by manual
// browser verification.
const ONLINE_FIXTURE =
  '/500/?serverUrl=ws://localhost:8080&playerName=Tester&animationSpeed=OFF&soundVolume=0';

test('connects to the server and creates a lobby', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto(ONLINE_FIXTURE);
  await awaitAppBoot(page);

  // Home -> online entry (name prefilled from the URL param) -> create form.
  await clickByRole(page, 'button', 'Play online');
  await clickByRole(page, 'button', 'Create a game');

  // Create the lobby with the default 4-player table. Exact match: a plain "Create" substring also
  // matches the entry screen's "Create a game" button, which can still be present mid-transition.
  await clickByRole(page, 'button', /^Create$/);

  // The server replied with a LobbyState: a 4-character join code and an open seat per empty spot.
  await expect(page.getByText(/^[0-9A-F]{4}$/).first()).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText('Open seat').first()).toBeVisible();
  // The creator sees the Start control (empty seats will become bots).
  await expect(page.getByRole('button', { name: /^Start/ })).toBeVisible();

  expect(errors, `console errors: ${errors.join('\n')}`).toEqual([]);
});

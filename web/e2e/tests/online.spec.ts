// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
import { test, expect, Page } from '@playwright/test';
import { awaitAppBoot, clickByRole, collectErrors } from './helpers';

// End-to-end online smoke: the browser (wasm) Ktor client talks to a real local game server over a
// WebSocket. This is the only test that exercises the browser client against the server, and it
// covers the whole integration seam that unit tests can only fake: connect, the Hello/Welcome
// handshake, CreateLobby, and rendering the server's LobbyState. Player name and server URL are
// injected as URL params so we avoid typing into the Compose canvas.
//
// Deliberately stops at lobby creation. A *guest* can't be driven further: readying needs the
// ready Switch, which Compose exposes on the canvas as a nameless button that Playwright can't
// reliably target (the known canvas-interaction limitation). The host-side start→bot-fill→gameplay
// path IS drivable (Start is a plain button) and is exercised by restart.spec.ts against its own
// server; guest gameplay is covered by the server's JVM integration tests instead.
const ONLINE_FIXTURE =
  '/500/?serverUrl=ws://localhost:8080&playerName=Tester&animationSpeed=OFF&soundVolume=0';

test('connects to the server and creates a lobby', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto(ONLINE_FIXTURE);
  await awaitAppBoot(page);

  // Home -> online entry (name prefilled from the URL param) -> create form.
  await clickByRole(page, 'button', 'Play with friends');
  await clickByRole(page, 'button', 'Create a game');

  // Create the lobby with the default 4-player table. Exact match: a plain "Create" substring also
  // matches the entry screen's "Create a game" button, which can still be present mid-transition.
  await clickByRole(page, 'button', /^Create$/);

  // The server replied with a LobbyState: a 4-character join code and an open seat per empty spot.
  // Codes use an unambiguous uppercase alphabet (no 0/1/I/O) — see RoomRegistry.CODE_ALPHABET.
  await expect(page.getByText(/^[2-9A-HJ-NP-Z]{4}$/).first()).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText('Open seat').first()).toBeVisible();
  // The creator sees the Start control (empty seats will become bots).
  await expect(page.getByRole('button', { name: /^Start/ })).toBeVisible();
  // The host can share an invite link (web: copies to clipboard).
  await expect(page.getByRole('button', { name: 'Share invite link' })).toBeVisible();

  expect(errors, `console errors: ${errors.join('\n')}`).toEqual([]);
});

/** Create a lobby as the host and return its join code (the shared preamble of the tests below). */
async function createLobby(page: Page): Promise<string> {
  await page.goto(ONLINE_FIXTURE);
  await awaitAppBoot(page);
  await clickByRole(page, 'button', 'Play with friends');
  await clickByRole(page, 'button', 'Create a game');
  await clickByRole(page, 'button', /^Create$/);
  const codeLocator = page.getByText(/^[2-9A-HJ-NP-Z]{4}$/).first();
  await expect(codeLocator).toBeVisible({ timeout: 15_000 });
  return (await codeLocator.textContent())!;
}

// Issue #11: the session token is persisted in the tab's sessionStorage, so a full page reload —
// which tears down the wasm instance and its WebSocket — can resume the seat it held; server-side
// the lobby seat is held through a short disconnect grace instead of disbanding on the drop.
// Stops at the rejoin offer: after a dialog closes, the wasm a11y mirror goes stale (the same
// canvas limitation noted above), so the actual re-entry is asserted by the invite-link test
// below, whose auto-rejoin path never shows a dialog.
test('a page reload offers the held lobby back via the persisted session token', async ({ page }) => {
  const errors = collectErrors(page);
  const code = await createLobby(page);

  await page.reload();
  await awaitAppBoot(page);
  await clickByRole(page, 'button', 'Play with friends');
  // The prompt names the exact room created before the reload: the token survived the reload AND
  // the server held the seat through the disconnect grace instead of disbanding the lobby.
  await expect(page.getByText(`You're still in game ${code}`)).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole('button', { name: /^Rejoin$/ })).toBeVisible();

  expect(errors, `console errors: ${errors.join('\n')}`).toEqual([]);
});

// Issue #11's headline case: the host opens their own invite link in the same tab — a full reload.
// The resumed room matches the link's code, so they land straight back in their lobby: no Join
// screen, no rejoin prompt, and crucially no disbanded room.
test('opening your own invite link returns you to your lobby', async ({ page }) => {
  const errors = collectErrors(page);
  const code = await createLobby(page);

  await page.goto(`${ONLINE_FIXTURE}&joinCode=${code}`);
  await expect(page.locator('#loading')).toHaveCount(0, { timeout: 60_000 });
  await expect(page.getByText(code).first()).toBeVisible({ timeout: 30_000 });
  // Only the creator's lobby shows the share button — proof we're the host again, not a joiner.
  await expect(page.getByRole('button', { name: 'Share invite link' })).toBeVisible();

  expect(errors, `console errors: ${errors.join('\n')}`).toEqual([]);
});

// An invite link (…/500/?joinCode=CODE) opens straight into online mode on the Join screen. Point
// serverUrl at the local test server so entering online mode never touches the production server;
// we only assert the routing (the code prefill itself is covered by the shared unit tests).
test('an invite link opens the join screen', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto('/500/?serverUrl=ws://localhost:8080&joinCode=ABCD&playerName=Tester&animationSpeed=OFF&soundVolume=0');
  // A joinCode link goes straight to online mode, so the home screen never shows — wait for the
  // loading placeholder to clear, then assert we landed on the Join screen (not home).
  await expect(page.locator('#loading')).toHaveCount(0, { timeout: 60_000 });
  await expect(page.getByText('Join a game')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('button', { name: /^Join$/ })).toBeVisible();

  expect(errors, `console errors: ${errors.join('\n')}`).toEqual([]);
});

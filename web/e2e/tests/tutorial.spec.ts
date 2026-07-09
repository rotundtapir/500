// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
import { expect, test } from '@playwright/test';
import { awaitAppBoot, clickByRole, collectErrors } from './helpers';

// The tutorial runs through the same GameViewModel wiring as a real game, so this is where the
// wasm-only boot pitfalls would surface: the explicit `viewModel { GameViewModel() }` factory (a
// bare viewModel() throws on wasm) shows up as an uncaught page error, and a broken symbol font as
// console errors. Playwright cannot drive the tutorial's scripted taps on wasm — Compose prunes the
// accessibility tree beneath the full-screen guidance overlay — so the scripted interaction is
// covered by the Android instrumented GameFlowTest; here we smoke-test that the path boots cleanly.
test('starting the tutorial boots the game screen on wasm without errors', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto('/500/?animationSpeed=OFF&soundVolume=0');
  await awaitAppBoot(page);

  await clickByRole(page, 'button', 'How to play');
  await expect(page.getByRole('button', { name: 'Start' })).toBeVisible({ timeout: 15_000 });
  await clickByRole(page, 'button', 'Start');

  // We left the home screen for the tutorial hand: its "Play offline" button is gone, the canvas is
  // still rendering, and nothing threw while the ViewModel spun up the scripted game.
  await expect(page.getByRole('button', { name: 'Play offline' })).toHaveCount(0, { timeout: 30_000 });
  await expect(page.locator('canvas')).toHaveCount(1);
  await page.waitForTimeout(1_000);
  expect(errors, 'the tutorial must boot console-error clean').toEqual([]);
});

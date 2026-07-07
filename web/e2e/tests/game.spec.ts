// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
import { expect, test } from '@playwright/test';
import { awaitAppBoot, clickByRole, collectErrors, FIXTURE } from './helpers';

// The seed-42 fixture (same as GameFlowTest's): Thelma opens the auction at 9♣; passing hands her
// the contract and play begins with clubs as trumps.
test('seeded game: suit glyphs render, bidding works, a trick completes', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto(FIXTURE);
  await awaitAppBoot(page);

  await clickByRole(page, 'button', 'New Game');

  // Bid ladder for the fixture — the suit symbols come through the accessibility tree, which
  // catches the missing-glyph (tofu) regression without a pixel diff.
  await expect(page.getByRole('button', { name: '10♠' })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('button', { name: '9♦' })).toBeVisible();
  await expect(page.getByText('high: 9♣')).toBeVisible();

  await clickByRole(page, 'button', 'Pass');

  // Thelma converts her 9♣ into the contract; it's our lead into the first trick.
  await expect(page.getByText('Contract: Thelma · 9♣')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText('Your turn — tap a card to play')).toBeVisible();

  // Follow trumps (Thelma led the Joker): play the Q♣ — playable cards surface as buttons in
  // the accessibility tree (unplayable ones are plain imgs). Bots finish the trick instantly at
  // animationSpeed=OFF and the sweep bumps a trick counter.
  await clickByRole(page, 'button', 'Q♣');
  await expect(page.getByText(/tricks: 1/).first()).toBeVisible({ timeout: 30_000 });

  expect(errors, 'game flow must be console-error clean').toEqual([]);
});

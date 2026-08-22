// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
import { expect, test } from '@playwright/test';
import { awaitAppBoot, clickByRole, collectErrors, FIXTURE } from './helpers';

// The About dialog is what bug reports are pointed at, so the values it shows must be the real
// build's — a broken AppBuildInfo/AppConfig wiring would render an empty or "unknown" report.
test('about dialog reports the build and copies it to the clipboard', async ({ page, context }) => {
  const errors = collectErrors(page);
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.goto(FIXTURE);
  await awaitAppBoot(page);

  await clickByRole(page, 'button', 'About');
  await expect(page.getByText('About 500')).toBeVisible({ timeout: 15_000 });

  // A real version (name + versionCode) and the web build line — not placeholders.
  await expect(page.getByText(/^\d+\.\d+\.\d+ \(\d+\)$/)).toBeVisible();
  await expect(page.getByText('web', { exact: true })).toBeVisible();

  // "Copy details" hands the same block to the clipboard for pasting into an issue.
  await clickByRole(page, 'button', 'Copy details');
  await expect(page.getByText('Copied to clipboard.')).toBeVisible();
  const clipboard = await page.evaluate(() => navigator.clipboard.readText());
  expect(clipboard).toMatch(/^Version: \d+\.\d+\.\d+ \(\d+\)$/m);
  expect(clipboard).toMatch(/^Commit: [0-9a-f]{7,}$/m); // a git-less build would say "unknown"
  expect(clipboard).toMatch(/^Build: web$/m);
  expect(clipboard).toMatch(/^Server: wss:\/\//m);

  // Dismissal is asserted on Android instead: closing a dialog leaves the wasm a11y mirror stale,
  // so nothing behind it can be located afterwards (see docs/e2e-coverage.md).
  expect(errors, 'about flow must be console-error clean').toEqual([]);
});

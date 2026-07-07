// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
import { expect, test } from '@playwright/test';
import { awaitAppBoot, collectErrors, FIXTURE } from './helpers';

test('document ships the loading placeholder for pre-boot and no-WasmGC browsers', async ({ request }) => {
  const html = await (await request.get('/500/')).text();
  expect(html).toContain('id="loading"');
  expect(html).toContain('Loading 500…');
});

test('boots clean: placeholder retired, home renders, zero console errors', async ({ page }, testInfo) => {
  const errors = collectErrors(page);
  await page.goto(FIXTURE);
  await awaitAppBoot(page);
  // Would have caught the JVM-only ViewModel-factory crash: the app composed nothing while the
  // console carried the uncaught exception.
  expect(errors, 'wasm boot must be console-error clean').toEqual([]);
  await testInfo.attach('home-screen', {
    body: await page.screenshot({ fullPage: true }),
    contentType: 'image/png',
  });
});

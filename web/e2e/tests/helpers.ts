// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
import { expect, Page } from '@playwright/test';

/** The deterministic fixture shared with the Android instrumentation suite (intent-extra mirror). */
export const FIXTURE = '/500/?seed=42&animationSpeed=OFF&soundVolume=0';

/**
 * Compose Multiplatform mirrors semantics into the accessibility tree with real bounding boxes,
 * but the canvas intercepts pointer events, so Playwright's actionability check on the mirror
 * element times out. Locate semantically, then click the canvas at the element's centre.
 */
export async function clickByRole(page: Page, role: 'button' | 'img', name: string | RegExp) {
  const locator = page.getByRole(role, { name }).first();
  await expect(locator).toBeVisible();
  const box = await locator.boundingBox();
  if (!box) throw new Error(`no bounding box for ${role} "${name}"`);
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
}

/**
 * Start collecting console errors and uncaught page errors. Register before goto().
 * Skiko probes WebGL extensions with warnings; only `error`-level entries are collected.
 */
export function collectErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(msg.text());
  });
  page.on('pageerror', (err) => errors.push(String(err)));
  return errors;
}

/** Wait for the wasm app to boot: the static placeholder is retired right before the first frame. */
export async function awaitAppBoot(page: Page) {
  await expect(page.locator('#loading')).toHaveCount(0, { timeout: 60_000 });
  await expect(page.getByRole('button', { name: 'New Game' })).toBeVisible({ timeout: 30_000 });
}

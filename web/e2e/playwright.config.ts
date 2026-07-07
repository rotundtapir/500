// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
import { defineConfig } from '@playwright/test';

// A smoke net over the production wasm distribution, served under a /500/ path prefix so every
// run rehearses the GitHub Pages subpath. Build the app first: ./gradlew :web:wasmJsBrowserDistribution
export default defineConfig({
  testDir: './tests',
  timeout: 90_000,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:9500',
    // System Chrome instead of a downloaded browser: WasmGC needs a current engine, GitHub
    // runners ship Chrome stable, and it mirrors what real users run.
    channel: 'chrome',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    viewport: { width: 500, height: 950 },
  },
  webServer: {
    command: 'node serve.mjs',
    url: 'http://localhost:9500/500/',
    reuseExistingServer: !process.env.CI,
  },
});

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
  webServer: [
    {
      command: 'node serve.mjs',
      url: 'http://localhost:9500/500/',
      reuseExistingServer: !process.env.CI,
    },
    {
      // The online game server, for online.spec.ts. Built by `./gradlew :server:installDist`.
      // DEV_MODE relaxes rate/connection caps; ALLOWED_ORIGINS=* lets the localhost page connect;
      // MIN_APP_VERSION=0.0.0 accepts whatever version the built web client reports.
      command:
        'PORT=8080 DEV_MODE=true ALLOWED_ORIGINS=* MIN_APP_VERSION=0.0.0 ' +
        '../../server/build/install/server/bin/server',
      url: 'http://localhost:8080/health',
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
  ],
});

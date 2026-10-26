import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173'
const backendURL = process.env.E2E_BACKEND_URL ?? 'http://127.0.0.1:18080'
export default defineConfig({
  testDir: './acceptance', fullyParallel: false, workers: 1, retries: 0, timeout: 240000,
  reporter: [['list'], ['html', { outputFolder: '../.artifacts/R-playwright-report', open: 'never' }], ['json', { outputFile: '../.artifacts/R-playwright.json' }]],
  outputDir: '../.artifacts/R-playwright-results',
  use: { baseURL, browserName: 'chromium', screenshot: 'only-on-failure', trace: 'off', video: 'on', actionTimeout: 15000, navigationTimeout: 30000 },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 1000 } } },
    { name: 'mobile', grepInvert: /\[desktop\]/, use: { ...devices['iPhone 13'], defaultBrowserType: 'chromium' } },
  ],
  webServer: process.env.E2E_EXTERNAL_WEB ? undefined : {
    command: 'node ./node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort', url: baseURL,
    reuseExistingServer: false, timeout: 30000, env: { ...process.env, DEV_BACKEND_URL: backendURL },
  },
})

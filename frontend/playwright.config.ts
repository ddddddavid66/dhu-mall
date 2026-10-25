import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:5173'
const backendURL = process.env.E2E_BACKEND_URL ?? 'http://127.0.0.1:18080'
const viteCommand = process.platform === 'win32' ? '.\\node_modules\\.bin\\vite.cmd' : './node_modules/.bin/vite'

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [
    ['list'],
    ['html', { outputFolder: '../.artifacts/G-playwright-report', open: 'never' }],
  ],
  outputDir: '../.artifacts/G-playwright-results',
  use: {
    baseURL,
    browserName: 'chromium',
    channel: 'msedge',
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    actionTimeout: 10000,
    navigationTimeout: 20000,
  },
  projects: [
    { name: 'desktop', grepInvert: /\[public\]/, use: { ...devices['Desktop Chrome'], channel: 'msedge', viewport: { width: 1440, height: 1000 }, trace: 'off' } },
    { name: 'mobile', grepInvert: /\[public\]/, use: { ...devices['iPhone 13'], channel: 'msedge', viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, trace: 'off' } },
    { name: 'public-mobile', grep: /\[public\]/, use: { ...devices['iPhone 13'], channel: 'msedge', viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, trace: 'on' } },
  ],
  webServer: {
    command: `${viteCommand} --host 127.0.0.1 --port 5173 --strictPort`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 30000,
    env: { ...process.env, DEV_BACKEND_URL: backendURL },
  },
})

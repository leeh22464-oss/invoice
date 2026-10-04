import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  timeout: 120_000,
  workers: 1,
  use: {
    baseURL: 'http://127.0.0.1:5173',
  },
  webServer: {
    command: 'node start-stack.mjs',
    cwd: __dirname,
    port: 5173,
    timeout: 360_000,
    reuseExistingServer: false,
  },
})

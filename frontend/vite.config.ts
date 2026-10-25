import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const backend = env.DEV_BACKEND_URL || 'http://127.0.0.1:8080'
  return {
    plugins: [vue()],
    server: {
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
      proxy: Object.fromEntries(
        ['/api/v1', '/actuator/health', '/v3/api-docs', '/swagger-ui'].map(path => [path, { target: backend }]),
      ),
    },
    test: {
      environment: 'jsdom',
      globals: true,
      include: ['tests/**/*.test.ts'],
      restoreMocks: true,
    },
  }
})

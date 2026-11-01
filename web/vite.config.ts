import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  // Only a backend address belongs here. No model key is exposed to the browser.
  const env = loadEnv(mode, process.cwd(), 'SHOPASSIST_')
  const proxy = { '/api': { target: env.SHOPASSIST_API_TARGET || 'http://127.0.0.1:8081', changeOrigin: true, timeout: 210_000, proxyTimeout: 210_000 } }
  return {
    plugins: [react()],
    server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy },
    preview: { host: '127.0.0.1', port: 4173, strictPort: true, proxy },
  }
})

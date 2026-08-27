import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// Neither backend service sends CORS headers - a direct browser call is rejected with
// "Invalid CORS request". Everything therefore goes through this dev-server proxy, which
// also means the app only ever talks to its own origin and needs no CORS config upstream.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  const management = env.MANAGEMENT_BASE_URL ?? 'http://localhost:8080'
  const runtime = env.RUNTIME_BASE_URL ?? 'http://localhost:8081'

  return {
    plugins: [react()],
    server: {
      port: Number(env.PORT ?? 5173),
      strictPort: true,
      proxy: {
        '/proxy/management': {
          target: management,
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/proxy\/management/, ''),
        },
        '/proxy/runtime': {
          target: runtime,
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/proxy\/runtime/, ''),
        },
      },
    },
  }
})

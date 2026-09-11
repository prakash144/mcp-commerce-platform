import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Dev-mode integration: the UI talks to the real services directly.
// The proxy keeps the browser same-origin (no CORS work on the backends),
// and mirrors how a production Kong gateway would route /api -> product, /graphql -> order.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/graphql': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
    },
  },
})
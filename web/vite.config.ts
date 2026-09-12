import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Dev-mode integration: the UI talks to the real services directly.
// The proxy keeps the browser same-origin (no CORS work on the backends),
// and mirrors how a production Kong gateway would route /api -> product, /graphql -> order.
// Inside docker-compose, WEB_PROXY_* env vars resolve services by container name;
// host mode uses the defaults (localhost ports).
const product = process.env.WEB_PROXY_PRODUCT ?? 'http://localhost:8081'
const order = process.env.WEB_PROXY_ORDER ?? 'http://localhost:8082'
const payment = process.env.WEB_PROXY_PAYMENT ?? 'http://localhost:8090'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: product,
        changeOrigin: true,
      },
      '/graphql': {
        target: order,
        changeOrigin: true,
      },
      '/v1': {
        target: payment,
        changeOrigin: true,
      },
    },
  },
})
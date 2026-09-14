import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Desarrollo local sin Docker: `npm run dev` sirve la SPA en 5173 y
    // proxya /api y /actuator al backend real en 8081 -- así el
    // frontend nunca necesita saber la URL del backend ni CORS.
    proxy: {
      '/api': 'http://localhost:8081',
      '/actuator': 'http://localhost:8081',
    },
  },
})

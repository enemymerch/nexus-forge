import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true, proxy: {
    '/api/taxonomy': { target: process.env.TAXONOMY_API_URL || 'http://localhost:8083', changeOrigin: true }
  }},
  test: { environment: 'jsdom', setupFiles: ['./src/test/setup.ts'], maxWorkers: 1 }
});

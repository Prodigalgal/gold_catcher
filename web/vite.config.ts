import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react(), {
    name: 'gold-catcher-release-meta',
    transformIndexHtml: { order: 'pre', handler: html => html.replace('%VITE_SOURCE_COMMIT%', process.env.VITE_SOURCE_COMMIT ?? 'development') },
  }],
  build: { modulePreload: false, cssCodeSplit: false },
  server: { proxy: { '/api': { target: process.env.GOLD_DEV_API_URL ?? 'http://127.0.0.1:8080', changeOrigin: true } } },
});

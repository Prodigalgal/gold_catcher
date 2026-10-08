import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react(), {
    name: 'gold-catcher-release-meta',
    transformIndexHtml: { order: 'pre', handler: html => html.replace('%VITE_SOURCE_COMMIT%', process.env.VITE_SOURCE_COMMIT ?? 'development') },
  }],
  build: { modulePreload: false, cssCodeSplit: false },
});

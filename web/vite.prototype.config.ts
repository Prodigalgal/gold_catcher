import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({ plugins:[react()],build:{outDir:'prototype-dist',modulePreload:false,cssCodeSplit:false,rolldownOptions:{input:'prototype.html'}} });

import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // O backend de dev publica a API em 8081 (mesma base usada pela TUI); o app chama `/api`
    // relativo e este proxy evita CORS.
    proxy: { '/api': 'http://localhost:8081' },
  },
});

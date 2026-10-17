import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return {
    plugins: [react()],
    server: { host: 'localhost', port: 3000, strictPort: true,
      proxy: { '/api': { target: env.DEVHUB_PROXY_TARGET || 'http://localhost:8080', changeOrigin: false } } },
    preview: { host: 'localhost', port: 3000, strictPort: true },
    test: { environment: 'jsdom', setupFiles: './src/test/setup.js', clearMocks: true },
  };
});

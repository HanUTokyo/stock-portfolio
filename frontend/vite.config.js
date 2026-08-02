import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const devApiProxyTarget = env.VITE_DEV_API_PROXY_TARGET || 'http://localhost:8080';
  const apiProxy = {
    '/api': {
      target: devApiProxyTarget,
      changeOrigin: true,
      secure: false
    }
  };

  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: apiProxy
    },
    preview: {
      proxy: apiProxy
    }
  };
});

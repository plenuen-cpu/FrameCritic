import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/** 정적 파일만 만든다. 서버 코드가 없으므로 Vercel에서도 그대로 정적 배포된다. */
export default defineConfig({
  plugins: [react()],
  build: {
    target: 'es2020',
    outDir: 'dist',
    sourcemap: false,
  },
})

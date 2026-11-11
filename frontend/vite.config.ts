import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

// 浏览器刷新页面时发出的是 HTML 导航请求（Accept 含 text/html），
// 必须回退到 index.html 由 vue-router 接管；
// 否则 /admin/users、/api-keys 等页面路径会撞上同名代理前缀被转发到网关，
// 得到后端的 401 JSON 或网关的 Whitelabel 404 页。
const spaFallback = (req: { headers: Record<string, unknown> }) => {
  const accept = String(req.headers.accept ?? '')
  if (accept.includes('text/html')) return '/index.html'
}

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/auth': { target: 'http://localhost:10011', bypass: spaFallback },
      '/v1': { target: 'http://localhost:10011', bypass: spaFallback },
      '/admin': { target: 'http://localhost:10011', bypass: spaFallback },
      '/api': { target: 'http://localhost:10011', bypass: spaFallback },
    },
  },
})

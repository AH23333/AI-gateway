import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as loginApi, register as registerApi } from '@/api/auth'
import type { LoginRequest, RegisterRequest } from '@/api/types'

const TOKEN_KEY = 'ai_gateway_token'
const REFRESH_KEY = 'ai_gateway_refresh'
const USER_KEY = 'ai_gateway_user'

function parseTokenPayload(token: string): Record<string, any> | null {
  try {
    const payload = token.split('.')[1]
    return JSON.parse(atob(payload))
  } catch {
    return null
  }
}

function parseTokenExp(token: string): number | null {
  const payload = parseTokenPayload(token)
  return payload?.exp || null
}

export const useUserStore = defineStore('user', () => {
  const token = ref('')
  const refreshToken = ref('')
  const userId = ref(0)
  const username = ref('')
  const role = ref('USER')

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => role.value === 'ADMIN')

  function isTokenExpired(): boolean {
    if (!token.value) return true
    const exp = parseTokenExp(token.value)
    if (!exp) return false
    return Date.now() >= exp * 1000
  }

  /** 从 localStorage 恢复登录状态 */
  function restoreFromStorage() {
    token.value = localStorage.getItem(TOKEN_KEY) || ''
    refreshToken.value = localStorage.getItem(REFRESH_KEY) || ''
    if (token.value) {
      const payload = parseTokenPayload(token.value)
      if (payload) {
        userId.value = payload.userId || 0
        username.value = payload.username || ''
        role.value = payload.role || 'USER'
      }
    }
    const user = localStorage.getItem(USER_KEY)
    if (user && !username.value) {
      try {
        const u = JSON.parse(user)
        userId.value = u.userId || 0
        username.value = u.username || ''
        role.value = u.role || 'USER'
      } catch { /* ignore */ }
    }
  }

  /** 初始化认证：恢复 token 并主动刷新过期 token */
  async function initAuth(): Promise<boolean> {
    restoreFromStorage()
    if (!token.value) return false

    if (!isTokenExpired()) return true

    if (!refreshToken.value) {
      clearTokens()
      return false
    }

    try {
      const { refreshToken: refreshApi } = await import('@/api/auth')
      const resp = await refreshApi(refreshToken.value)
      setTokens(resp.data.accessToken, resp.data.refreshToken)
      return true
    } catch {
      clearTokens()
      return false
    }
  }

  /** 持久化 Token 到 localStorage，并从 JWT 解析角色 */
  function setTokens(access: string, refresh: string) {
    token.value = access
    refreshToken.value = refresh
    localStorage.setItem(TOKEN_KEY, access)
    localStorage.setItem(REFRESH_KEY, refresh)
    // 从 JWT 解析用户信息
    const payload = parseTokenPayload(access)
    if (payload) {
      userId.value = payload.userId || 0
      username.value = payload.username || ''
      role.value = payload.role || 'USER'
      localStorage.setItem(USER_KEY, JSON.stringify({
        userId: payload.userId,
        username: payload.username,
        role: payload.role || 'USER'
      }))
    }
  }

  /** 清除 Token */
  function clearTokens() {
    token.value = ''
    refreshToken.value = ''
    userId.value = 0
    username.value = ''
    role.value = 'USER'
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_KEY)
    localStorage.removeItem(USER_KEY)
  }

  /** 登录 */
  async function login(data: LoginRequest) {
    const resp = await loginApi(data)
    setTokens(resp.data.accessToken, resp.data.refreshToken)
  }

  /** 注册 */
  async function register(data: RegisterRequest) {
    const resp = await registerApi(data)
    setTokens(resp.data.accessToken, resp.data.refreshToken)
  }

  /** 退出登录 */
  async function logout() {
    try {
      if (refreshToken.value) {
        const { logout: logoutApi } = await import('@/api/auth')
        await logoutApi(refreshToken.value)
      }
    } finally {
      clearTokens()
    }
  }

  return { token, refreshToken, userId, username, role, isLoggedIn, isAdmin, restoreFromStorage, initAuth, setTokens, clearTokens, login, register, logout }
})
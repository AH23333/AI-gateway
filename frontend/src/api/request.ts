import axios from 'axios'
import type { AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import router from '@/router'

const http: AxiosInstance = axios.create({
  baseURL: '',
  timeout: 180000,
  headers: { 'Content-Type': 'application/json' },
})

let isRefreshing = false
let refreshSubscribers: ((token: string) => void)[] = []

function onRefreshed(token: string) {
  refreshSubscribers.forEach(cb => cb(token))
  refreshSubscribers = []
}

function addRefreshSubscriber(cb: (token: string) => void) {
  refreshSubscribers.push(cb)
}

// 请求拦截器：自动附加 Token
http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const userStore = useUserStore()
  // /auth/refresh 请求不附加 Authorization 头，refreshToken 在请求体中传递
  if (userStore.token && config.headers && !config.url?.includes('/auth/refresh')) {
    config.headers.Authorization = `Bearer ${userStore.token}`
  }
  return config
})

// 响应拦截器：统一错误处理 + Token 自动刷新
http.interceptors.response.use(
  (response: AxiosResponse) => {
    // 后端统一返回格式 { code, msg, data }
    const body = response.data
    if (body && body.code !== undefined && body.code !== 200) {
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(new Error(body.msg))
    }
    return body
  },
  async (error) => {
    const { config, response } = error

    // /auth/refresh 自身失败不触发刷新逻辑，避免无限递归
    if (response?.status === 401 && config?.url?.includes('/auth/refresh')) {
      const userStore = useUserStore()
      userStore.clearTokens()
      router.push('/login')
      return Promise.reject(error)
    }

    if (response?.status === 401) {
      const userStore = useUserStore()
      if (!userStore.refreshToken) {
        userStore.clearTokens()
        router.push('/login')
        return Promise.reject(error)
      }

      // 如果正在刷新，将当前请求加入等待队列
      if (isRefreshing) {
        return new Promise(resolve => {
          addRefreshSubscriber((token: string) => {
            config.headers.Authorization = `Bearer ${token}`
            resolve(http(config))
          })
        })
      }

      isRefreshing = true
      try {
        const resp = await http.post('/auth/refresh', {
          refreshToken: userStore.refreshToken,
        })
        const { accessToken, refreshToken } = resp.data
        userStore.setTokens(accessToken, refreshToken)
        onRefreshed(accessToken)
        // 重试原请求
        config.headers.Authorization = `Bearer ${accessToken}`
        return http(config)
      } catch {
        userStore.clearTokens()
        refreshSubscribers = []
        router.push('/login')
        return Promise.reject(error)
      } finally {
        isRefreshing = false
      }
    }

    ElMessage.error(error.response?.data?.msg || error.message || '网络错误')
    return Promise.reject(error)
  },
)

export default http
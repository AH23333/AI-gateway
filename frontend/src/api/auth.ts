import http from './request'
import type { TokenResponse, LoginRequest, RegisterRequest, ApiResponse, PublicSettings } from './types'

export function login(data: LoginRequest): Promise<ApiResponse<TokenResponse>> {
  return http.post('/auth/login', data)
}

export function register(data: RegisterRequest): Promise<ApiResponse<TokenResponse>> {
  return http.post('/auth/register', data)
}

export function refreshToken(refreshToken: string): Promise<ApiResponse<TokenResponse>> {
  return http.post('/auth/refresh', { refreshToken })
}

export function logout(refreshToken: string): Promise<ApiResponse<null>> {
  return http.post('/auth/logout', { refreshToken })
}

/** 公开站点设置（免认证：登录页站点名/公告，v2.4 设置中心） */
export function getPublicSettings(): Promise<ApiResponse<PublicSettings>> {
  return http.get('/settings/public')
}
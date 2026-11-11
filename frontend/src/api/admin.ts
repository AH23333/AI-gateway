import http from './request'
import type {
  ApiResponse, Page, User, UserInfo, ModelInfo, ModelUpsertRequest, ModelPrice,
  ApiKeyVO, ModelOption, AdminDashboard, InvokeLog, ChatMessageRecord, ChatLogDetail,
  RoutingConfig, RoutingAnalysisItem, RuntimeMetrics, SystemConfig, AuditLog, AuditLogQuery,
} from './types'

// ==================== 用户管理（/admin/users） ====================

export function getUsers(params: { page: number; size: number; keyword?: string }): Promise<ApiResponse<Page<User>>> {
  return http.get('/admin/users', { params })
}

export function updateUserStatus(userId: string, status: number): Promise<ApiResponse<null>> {
  return http.put(`/admin/users/${userId}/status`, null, { params: { status } })
}

export function updateUserQuota(userId: string, dailyQuota: number, monthlyQuota: number): Promise<ApiResponse<null>> {
  return http.put(`/admin/users/${userId}/quota`, { dailyQuota, monthlyQuota })
}

export function updateUserRole(userId: string, role: string): Promise<ApiResponse<null>> {
  return http.put(`/admin/users/${userId}/role`, null, { params: { role } })
}

// ==================== 管理端看板与用量（/admin） ====================

export function getDashboardStats(): Promise<ApiResponse<AdminDashboard>> {
  return http.get('/admin/dashboard')
}

/** 全量调用日志分页（管理端用量页，跨用户） */
export function getUsage(params: { page: number; size: number }): Promise<ApiResponse<Page<InvokeLog>>> {
  return http.get('/admin/usage', { params })
}

// ==================== 管理端 API Key（/admin/apikeys） ====================

export function getAdminApiKeys(params: { page: number; size: number }): Promise<ApiResponse<Page<ApiKeyVO>>> {
  return http.get('/admin/apikeys', { params })
}

export function revokeAdminApiKey(id: number): Promise<ApiResponse<null>> {
  return http.delete(`/admin/apikeys/${id}`)
}

// ==================== 模型管理（/admin/models，v2） ====================

/** 模型列表（含禁用，非分页） */
export function getAdminModels(): Promise<ApiResponse<ModelInfo[]>> {
  return http.get('/admin/models')
}

export function createModel(data: ModelUpsertRequest): Promise<ApiResponse<ModelInfo>> {
  return http.post('/admin/models', data)
}

export function updateModel(id: number, data: ModelUpsertRequest): Promise<ApiResponse<ModelInfo>> {
  return http.put(`/admin/models/${id}`, data)
}

export function deleteModel(id: number): Promise<ApiResponse<null>> {
  return http.delete(`/admin/models/${id}`)
}

/** 价格版本历史（版本化改价，历史账单可复算） */
export function getModelPrices(id: number): Promise<ApiResponse<ModelPrice[]>> {
  return http.get(`/admin/models/${id}/prices`)
}

/** 新增价格版本（version+1，立即生效） */
export function addModelPrice(id: number, data: { priceInput: number; priceOutput: number }): Promise<ApiResponse<ModelPrice>> {
  return http.post(`/admin/models/${id}/prices`, data)
}

// ==================== 智能路由管理（/admin/routing，v2） ====================

export function getRoutingConfig(): Promise<ApiResponse<RoutingConfig>> {
  return http.get('/admin/routing/config')
}

/** 路由总开关热更新（0=直连缺省模型 1=智能路由） */
export function updateRoutingSwitch(enabled: number): Promise<ApiResponse<null>> {
  return http.put('/admin/routing/switch', null, { params: { enabled } })
}

/** 打分权重热更新（w_q + w_c = 1） */
export function updateRoutingWeights(weightQuality: number, weightCost: number): Promise<ApiResponse<null>> {
  return http.put('/admin/routing/weights', null, { params: { weightQuality, weightCost } })
}

/** 逐模型实时路由分析（质量/延迟/健康分/熔断/价格） */
export function getRoutingAnalysis(): Promise<ApiResponse<RoutingAnalysisItem[]>> {
  return http.get('/admin/routing/analysis')
}

/** 网关运行时指标总览（观测域：调用窗口/JVM/线程/批量写队列水位/依赖探活） */
export function getRuntimeMetrics(): Promise<ApiResponse<RuntimeMetrics>> {
  return http.get('/admin/metrics/runtime')
}

/** 智能路由决策日志分页（可解释性回放） */
export function getRoutingDecisions(params: { page: number; size: number }): Promise<ApiResponse<Page<InvokeLog>>> {
  return http.get('/admin/routing/decisions', { params })
}

// ==================== 用户自助（/api/user） ====================

/** 个人信息 + 配额水位 + 今日/本月用量 */
export function getUserInfo(): Promise<ApiResponse<UserInfo>> {
  return http.get('/api/user/info')
}

/** 我的 API Key 列表（脱敏，仅 keyPrefix） */
export function getUserApiKeys(): Promise<ApiResponse<ApiKeyVO[]>> {
  return http.get('/api/user/apikeys')
}

/** 创建 API Key（明文 fullKey 仅本次返回） */
export function createUserApiKey(data: { keyName: string }): Promise<ApiResponse<ApiKeyVO>> {
  return http.post('/api/user/apikeys', data)
}

export function revokeUserApiKey(id: number): Promise<ApiResponse<null>> {
  return http.delete(`/api/user/apikeys/${id}`)
}

/** 我的调用日志分页（用量页/仪表盘最近调用共用） */
export function getUserUsage(params: { page: number; size: number }): Promise<ApiResponse<Page<InvokeLog>>> {
  return http.get('/api/user/chat/logs', { params })
}

/** 模型列表（对话页选择器） */
export function getModelOptions(): Promise<ApiResponse<ModelOption[]>> {
  return http.get('/api/user/models')
}

// ==================== 对话历史（/api/user/chat，v2） ====================

/** 我的调用日志分页（浏览模式） */
export function getUserChatLogs(params: { page: number; size: number }): Promise<ApiResponse<Page<InvokeLog>>> {
  return http.get('/api/user/chat/logs', { params })
}

/** 会话内容关键词检索（ngram 全文索引，返回命中消息） */
export function searchChatMessages(params: { keyword: string; page: number; size: number }): Promise<ApiResponse<Page<ChatMessageRecord>>> {
  return http.get('/api/user/chat/search', { params })
}

/** 调用详情：日志 + 该次调用全部会话消息 */
export function getChatLogDetail(traceId: string): Promise<ApiResponse<ChatLogDetail>> {
  return http.get(`/api/user/chat/logs/${traceId}`)
}

// ==================== 系统设置（/admin/settings，v2.4 设置中心） ====================

export function getSystemSettings(): Promise<ApiResponse<SystemConfig[]>> {
  return http.get('/admin/settings')
}

/** 单键更新（不同键校验规则不同，逐键保存；即时生效免重启） */
export function updateSystemSetting(data: { key: string; value: string }): Promise<ApiResponse<SystemConfig>> {
  return http.put('/admin/settings', data)
}

// ==================== 操作审计（/admin/audit-logs，v2.5 操作审计） ====================

/** 审计日志分页检索（who/when/what/结果，逐项可选过滤） */
export function getAuditLogs(params: AuditLogQuery): Promise<ApiResponse<Page<AuditLog>>> {
  return http.get('/admin/audit-logs', { params })
}

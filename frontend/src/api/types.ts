// ==================== 通用响应类型 ====================

export interface ApiResponse<T = any> {
  code: number
  msg: string
  data: T
}

/** v2 后端统一分页结构 PageResult */
export interface Page<T> {
  records: T[]
  total: number
  current: number
  size: number
  pages: number
  hasNext: boolean
  hasPrevious: boolean
}

// ==================== 认证相关 ====================

export interface TokenResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
}

export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest {
  username: string
  email: string
  password: string
}

// ==================== 对话相关 ====================

export interface ChatMessagePayload {
  role: 'user' | 'assistant' | 'system'
  content: string
}

// ==================== 模型相关（v2 t_model） ====================

/** t_model 实体 */
export interface ModelInfo {
  id: number
  modelCode: string
  modelName: string
  /** 上游推理端点（OpenAI 兼容 /chat/completions 完整 URL） */
  endpoint: string
  /** 上游 API Key（编辑时传空 = 保持原值） */
  apiKey: string | null
  /** 模型池等级：cheap=便宜池 / strong=强模型池 / universal=全池通用 */
  modelTier: 'cheap' | 'strong' | 'universal'
  fallbackCode: string | null
  /** 冷启动基准质量分 [0,100] */
  baselineQuality: number
  /** 冷启动基准延迟（毫秒） */
  baselineLatencyMs: number
  /** 状态：0=禁用, 1=启用 */
  status: number
  gmtCreate: string
  gmtModified: string
}

/** 模型新增/编辑请求体（/admin/models） */
export interface ModelUpsertRequest {
  modelCode: string
  modelName: string
  endpoint: string
  /** 编辑时留空 = 保持原值不覆盖 */
  apiKey?: string
  modelTier: string
  fallbackCode?: string | null
  baselineQuality?: number
  baselineLatencyMs?: number
  status?: number
}

/** 价格版本（t_model_price，版本化改价） */
export interface ModelPrice {
  id: number
  modelCode: string
  version: number
  /** 输入单价（元/千Token） */
  priceInput: number
  /** 输出单价（元/千Token） */
  priceOutput: number
  effectiveTime: string
  /** 状态：0=停用, 1=启用 */
  status: number
  gmtCreate: string
}

/** 前端模型选择器选项 */
export interface ModelOption {
  name: string
  displayName: string
}

// ==================== API Key 相关（v2 ApiKeyVO） ====================

/**
 * ApiKeyVO —— 明文 fullKey 仅创建接口返回一次，列表永远只有 keyPrefix。
 * 关闭弹窗后无法再次查看明文。
 */
export interface ApiKeyVO {
  id: number
  userId: number
  keyName: string
  /** 明文前缀（sk-xxxx 前 8 位，列表辨识用） */
  keyPrefix: string
  /** 完整明文：仅创建时非空 */
  fullKey?: string | null
  /** 状态：0=已吊销, 1=有效 */
  status: number
  gmtCreate: string
  gmtExpire: string | null
}

// ==================== 调用日志（v2 t_invoke_log_v2） ====================

/** 调用日志（用量/历史/决策共用行结构） */
export interface InvokeLog {
  id: string
  traceId: string
  userId: number
  modelCode: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  costAmount: number
  latencyMs: number
  /** 首_token_延迟（毫秒，仅流式有值） */
  ttftMs: number | null
  /** 1=成功, 0=失败/中断 */
  success: number
  /** 1=使用了降级模型 */
  fallbackUsed: number
  /** 1=流式中断/取消 */
  interrupted: number
  /** 智能路由决策 JSON（指定模型时为 null） */
  routingDecision: string | null
  agentTaskId: string | null
  payloadRef: string | null
  /** 结算时使用的价格版本号 */
  priceVersion: number
  gmtCreate: string
}

/** 会话消息（t_chat_message，检索结果/详情回放） */
export interface ChatMessageRecord {
  id: number
  traceId: string
  userId: number
  role: string
  content: string
  gmtCreate: string
}

/** 调用详情：{ log: 调用日志, messages: 该次全部会话消息 } */
export interface ChatLogDetail {
  log: InvokeLog
  messages: ChatMessageRecord[]
}

// ==================== 用户相关（v2 t_user） ====================

export interface User {
  id: string
  username: string
  email: string
  dailyQuota: number
  monthlyQuota: number
  usedDaily: number
  usedMonthly: number
  /** 在途预扣水位（两段式计费） */
  reservedDaily: number
  reservedMonthly: number
  role: string
  status: number
  gmtCreate: string
  gmtModified: string
}

/** 用量统计块（今日/本月聚合） */
export interface UsageStats {
  calls: number
  successCalls: number
  tokens: number
  cost: number
}

/** /api/user/info —— 个人信息 + 今日/本月用量 */
export interface UserInfo extends User {
  todayUsage: UsageStats
  monthUsage: UsageStats
}

// ==================== 仪表盘（v2 /admin/dashboard） ====================

export interface TrendPoint {
  statDate: string
  calls: number
  tokens: number
  cost: number | string
}

export interface ModelRankItem {
  modelCode: string
  calls: number
  tokens: number
  cost: number | string
}

export interface AdminDashboard {
  today: UsageStats & { failedCalls: number; successRate: number }
  modelDistribution7d: ModelRankItem[]
  trend7d: TrendPoint[]
  totalUsers: number
  activeModels: number
  /** 路由总开关：1=开启, 0=关闭 */
  routingEnabled: number
}

// ==================== 智能路由（v2 /admin/routing） ====================

/** t_routing_config（单行配置表） */
export interface RoutingConfig {
  id: number
  enabled: number
  weightQuality: number
  weightCost: number
  gmtModified: string
}

/** 逐模型实时路由分析行 */
export interface RoutingAnalysisItem {
  modelCode: string
  modelName: string
  modelTier: string
  fallbackCode: string | null
  baselineQuality: number
  baselineLatencyMs: number
  qualitySamples: number
  avgQuality: number | null
  p90LatencyMs: number | null
  healthScore: number
  blacklisted: boolean
  blacklistReason?: string
  priceInput: number | null
  priceOutput: number | null
  priceVersion: number | null
}

/** 网关运行时指标总览（观测域，/admin/metrics/runtime） */
export interface RuntimeMetrics {
  version: string
  bootTimeMs: number
  uptimeSeconds: number
  calls: {
    totalCalls: number
    inFlight: number
    activeStreams: number
    /** 60 秒逐秒调用数（旧 → 新） */
    perSecond: number[]
    qps60s: number
    qps10s: number
  }
  jvm: {
    heapUsed: number
    heapCommitted: number
    heapMax: number
    nonHeapUsed: number
    nonHeapCommitted: number
  }
  threads: {
    threadCount: number
    peakThreadCount: number
    daemonThreadCount: number
    availableProcessors: number
  }
  batchWriter: {
    messageQueueSize: number
    billingQueueSize: number
    queueCapacity: number
    flushIntervalMs: number
  }
  redis: { status: string; latencyMs: number }
  db: { status: string; latencyMs: number }
}

// ==================== 系统设置（v2.4 /admin/settings） ====================

/** t_system_config 行（设置页） */
export interface SystemConfig {
  configKey: string
  configValue: string
  description: string
  updatedBy: number | null
  gmtModified: string
}

/** /settings/public 免认证（登录页站点名/公告） */
export interface PublicSettings {
  siteName: string
  announcement: string
}

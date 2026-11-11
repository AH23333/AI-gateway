import http from './request'
import { useUserStore } from '@/stores/user'
import type { ModelOption, ApiResponse, ChatMessagePayload } from './types'

/**
 * 阻塞对话（OpenAI 兼容协议）
 *
 * 注意：/v1/chat/completions 非流式响应为 OpenAI chat.completion JSON
 * （非 Result{code,msg,data} 包装），响应拦截器按无 code 字段原样放行。
 */
export async function sendChat(data: { model: string; messages: ChatMessagePayload[] }): Promise<any> {
  return http.post('/v1/chat/completions', { ...data, stream: false })
}

export interface StreamHandlers {
  /** 收到一段回答增量文本 */
  onDelta: (text: string) => void
  /** 收到一段思考过程增量（reasoning_content，思考型模型透传，不入库不计费） */
  onReasoning?: (text: string) => void
  /** 业务/服务错误提示 */
  onError?: (msg: string) => void
  /** 流正常结束（携带响应头里的路由决策与 traceId） */
  onDone?: (info: { traceId?: string; decision?: string }) => void
}

/**
 * 流式对话（fetch + ReadableStream，axios 不支持浏览器端流式读取）
 *
 * v2 线上协议（OpenAI 兼容）：
 *   data: {"choices":[{"delta":{"content":"增量"}}]}              —— 回答增量
 *   data: {"choices":[{"delta":{"reasoning_content":"增量"}}]}    —— 思考过程增量
 *   data: {"choices":[{"delta":{},"finish_reason":"stop"}]}       —— 收尾帧
 *   data: {"error":{"message":"..","type":"api_error","code":..}} —— 流中错误
 *   data: [DONE]                                                  —— 结束标记
 * 响应头 X-Routing-Decision（路由摘要）/ X-Trace-Id（链路追踪）。
 */
export async function sendChatStream(
  params: { model: string; messages: ChatMessagePayload[] },
  handlers: StreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  const userStore = useUserStore()
  const resp = await fetch('/v1/chat/completions', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(userStore.token ? { Authorization: `Bearer ${userStore.token}` } : {}),
    },
    body: JSON.stringify({ model: params.model, messages: params.messages, stream: true }),
    signal,
  })

  if (!resp.ok || !resp.body) {
    let msg = `HTTP ${resp.status}`
    try {
      const j = await resp.json()
      msg = j?.error?.message || j?.msg || msg
    } catch { /* ignore */ }
    handlers.onError?.(msg)
    return
  }

  // 路由决策与 traceId 在响应头里（流开始前即可读取）
  const decision = resp.headers.get('X-Routing-Decision') || undefined
  const traceId = resp.headers.get('X-Trace-Id') || undefined

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  const handleFrame = (frame: string) => {
    // SSE 帧：拼接所有 data: 行（一个事件可多行 data）；注释行（:keep-alive）自然跳过
    const dataLines = frame.split('\n').filter((l) => l.startsWith('data:'))
    if (dataLines.length === 0) return
    const data = dataLines.map((l) => l.slice(5).trim()).join('')
    if (!data) return
    if (data === '[DONE]') return
    let payload: any
    try {
      payload = JSON.parse(data)
    } catch {
      return
    }
    if (payload?.error) {
      handlers.onError?.(payload.error.message || '生成失败')
      return
    }
    const delta = payload?.choices?.[0]?.delta
    if (!delta) return
    if (typeof delta.reasoning_content === 'string' && delta.reasoning_content.length > 0) {
      handlers.onReasoning?.(delta.reasoning_content)
    }
    if (typeof delta.content === 'string' && delta.content.length > 0) {
      handlers.onDelta(delta.content)
    }
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // SSE 帧以空行分隔
      let idx: number
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        if (frame.trim()) handleFrame(frame)
      }
    }
    if (buffer.trim()) handleFrame(buffer)
    handlers.onDone?.({ traceId, decision })
  } finally {
    reader.releaseLock()
  }
}

/** 获取可用的模型列表（启用态） */
export function getModels(): Promise<ApiResponse<ModelOption[]>> {
  return http.get('/api/user/models')
}

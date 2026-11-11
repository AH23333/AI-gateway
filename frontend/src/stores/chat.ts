import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface LocalMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  /** 思考过程（思考型模型透传 reasoning_content，仅前端展示，不入库不计费） */
  reasoning?: string
  /** 路由决策摘要（响应头 X-Routing-Decision，如 auto:glm-4.5-flash） */
  decision?: string
  /** 链路追踪 ID（响应头 X-Trace-Id，可用于对话历史定位本次调用） */
  traceId?: string
  timestamp: number
}

export interface Conversation {
  id: string
  title: string
  messages: LocalMessage[]
  model: string
  createdAt: number
  updatedAt: number
}

export interface ModelOption {
  name: string
  displayName: string
}

export const useChatStore = defineStore('chat', () => {
  const conversations = ref<Conversation[]>([])
  const currentConversationId = ref<string | null>(null)
  const models = ref<ModelOption[]>([])
  const currentModel = ref('')
  const isSending = ref(false)
  const abortController = ref<AbortController | null>(null)

  const currentConversation = computed(() =>
    conversations.value.find(c => c.id === currentConversationId.value) ?? null
  )

  const messages = computed(() => currentConversation.value?.messages ?? [])

  function genId(): string {
    return Date.now().toString(36) + Math.random().toString(36).slice(2)
  }

  function createConversation(title?: string): string {
    const id = genId()
    conversations.value.unshift({
      id,
      title: title || '新对话',
      messages: [],
      model: currentModel.value,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    })
    currentConversationId.value = id
    return id
  }

  function switchConversation(id: string) {
    currentConversationId.value = id
  }

  function deleteConversation(id: string) {
    const idx = conversations.value.findIndex(c => c.id === id)
    if (idx === -1) return
    conversations.value.splice(idx, 1)
    if (currentConversationId.value === id) {
      currentConversationId.value = conversations.value[0]?.id ?? null
    }
  }

  function updateConversationTitle(id: string, title: string) {
    const conv = conversations.value.find(c => c.id === id)
    if (conv) conv.title = title
  }

  function addMessage(msg: LocalMessage) {
    const conv = conversations.value.find(c => c.id === currentConversationId.value)
    if (!conv) return
    conv.messages.push(msg)
    conv.updatedAt = Date.now()
    // 自动用第一条用户消息作为标题
    if (conv.title === '新对话' && msg.role === 'user') {
      conv.title = msg.content.slice(0, 30) + (msg.content.length > 30 ? '...' : '')
    }
  }

  /** 增量更新最后一条 AI 消息（正文/思考过程/路由元信息） */
  function updateLastAssistant(patch: Partial<LocalMessage>) {
    const conv = conversations.value.find(c => c.id === currentConversationId.value)
    if (!conv) return
    const last = conv.messages[conv.messages.length - 1]
    if (last && last.role === 'assistant') {
      Object.assign(last, patch)
      conv.updatedAt = Date.now()
    }
  }

  function clearCurrentMessages() {
    const conv = conversations.value.find(c => c.id === currentConversationId.value)
    if (conv) {
      conv.messages = []
      conv.title = '新对话'
    }
  }

  function cancelRequest() {
    abortController.value?.abort()
    abortController.value = null
    isSending.value = false
  }

  return {
    conversations,
    currentConversationId,
    models,
    currentModel,
    isSending,
    abortController,
    currentConversation,
    messages,
    genId,
    createConversation,
    switchConversation,
    deleteConversation,
    updateConversationTitle,
    addMessage,
    updateLastAssistant,
    clearCurrentMessages,
    cancelRequest,
  }
})
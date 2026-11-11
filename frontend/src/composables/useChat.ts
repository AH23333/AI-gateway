import { ref, nextTick } from 'vue'
import { sendChatStream, getModels as getModelsApi } from '@/api/chat'
import type { ChatMessagePayload } from '@/api/types'
import { useChatStore, type LocalMessage } from '@/stores/chat'

/** 智能路由选项：model=auto 时后端强制走智能路由打分 */
export const AUTO_MODEL: { name: string; displayName: string } = {
  name: 'auto',
  displayName: '智能路由 · 自动',
}

export function useChat() {
  const store = useChatStore()

  const scrollToBottom = ref<(() => void) | null>(null)

  function genId(): string {
    return Date.now().toString(36) + Math.random().toString(36).slice(2)
  }

  function ensureConversation() {
    if (!store.currentConversationId) {
      store.createConversation()
    }
  }

  async function doScroll() {
    await nextTick()
    scrollToBottom.value?.()
  }

  /** 发送消息（流式，支持思考过程增量与路由元信息回填） */
  async function sendMessage(content: string) {
    if (!content.trim() || store.isSending) return

    ensureConversation()

    const userMsg: LocalMessage = {
      id: genId(),
      role: 'user',
      content,
      timestamp: Date.now(),
    }
    store.addMessage(userMsg)
    store.isSending = true

    await doScroll()

    // 组装上游消息（思考过程 reasoning 不回传，只传正文）
    const apiMessages: ChatMessagePayload[] = store.messages
      .filter((m) => m.role !== 'system')
      .map((m) => ({ role: m.role, content: m.content }))

    const controller = new AbortController()
    store.abortController = controller

    // 添加空的 AI 回复占位
    const aiMsg: LocalMessage = {
      id: genId(),
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
    }
    store.addMessage(aiMsg)

    try {
      let acc = ''
      let rAcc = ''
      let errored = false
      await sendChatStream(
        { model: store.currentModel || AUTO_MODEL.name, messages: apiMessages },
        {
          onDelta(text) {
            acc += text
            store.updateLastAssistant({ content: acc })
          },
          onReasoning(text) {
            rAcc += text
            store.updateLastAssistant({ reasoning: rAcc })
          },
          onError(msg) {
            errored = true
            store.updateLastAssistant({ content: msg || '抱歉，请求失败，请稍后重试。' })
          },
          // 流正常结束：回填响应头里的路由决策与链路追踪 ID
          onDone({ traceId, decision }) {
            store.updateLastAssistant({
              ...(traceId ? { traceId } : {}),
              ...(decision ? { decision } : {}),
            })
          },
        },
        controller.signal,
      )
      if (!acc && !errored) {
        store.updateLastAssistant({ content: '抱歉，未能获取回复。' })
      }
    } catch (err: any) {
      if (err?.name === 'AbortError') {
        // 用户主动停止：保留已生成的部分内容
        const cur = store.messages[store.messages.length - 1]
        if (cur && cur.role === 'assistant' && !cur.content) {
          store.updateLastAssistant({ content: '_(已停止生成)_' })
        }
      } else {
        store.updateLastAssistant({ content: '抱歉，请求失败，请稍后重试。' })
      }
    } finally {
      store.isSending = false
      store.abortController = null
      await doScroll()
    }
  }

  /** 重新生成最后一次回复 */
  async function regenerate() {
    const msgs = store.messages
    if (msgs.length === 0) return

    // 移除最后一条 AI 消息
    const conv = store.currentConversation
    if (conv && conv.messages.length > 0 && conv.messages[conv.messages.length - 1].role === 'assistant') {
      conv.messages.pop()
    }

    // 找到最后一条用户消息重新发送
    const lastUserMsg = [...msgs].reverse().find(m => m.role === 'user')
    if (lastUserMsg) {
      await sendMessage(lastUserMsg.content)
    }
  }

  /** 停止生成 */
  function stopGeneration() {
    store.cancelRequest()
  }

  /** 加载模型列表（首位固定插入"智能路由·自动"选项） */
  async function loadModels() {
    try {
      const resp = await getModelsApi()
      store.models = [AUTO_MODEL, ...(resp.data || [])]
    } catch {
      store.models = [AUTO_MODEL]
    } finally {
      if (!store.currentModel) {
        store.currentModel = AUTO_MODEL.name
      }
    }
  }

  return { sendMessage, regenerate, stopGeneration, loadModels, scrollToBottom }
}

<template>
  <div class="chat-page">
    <!-- 顶部栏 -->
    <div class="chat-header">
      <div class="header-left">
        <h2 v-if="chatStore.currentConversation?.title" class="conv-title">
          {{ chatStore.currentConversation?.title }}
        </h2>
      </div>
      <div class="header-right">
        <el-button text size="small" @click="handleNewChat">
          <el-icon :size="16"><Plus /></el-icon>
          新对话
        </el-button>
      </div>
    </div>

    <!-- 消息区域 -->
    <div class="chat-messages" ref="messagesContainer">
      <!-- 欢迎页 -->
      <div v-if="chatStore.messages.length === 0" class="welcome-screen">
        <div class="welcome-kicker mono">GATEWAY / CHAT</div>
        <h1 class="welcome-title">有什么可以帮你的？</h1>
        <p class="welcome-desc">请求将经由智能路由分发至最合适的模型</p>
        <div class="suggestions">
          <button
            v-for="(prompt, idx) in suggestedPrompts"
            :key="idx"
            class="suggestion-card"
            @click="sendMessage(prompt.content)"
          >
            <span class="suggestion-prefix mono">&gt;</span>
            <div class="suggestion-text">
              <div class="suggestion-title">{{ prompt.title }}</div>
              <div class="suggestion-desc">{{ prompt.desc }}</div>
            </div>
            <el-icon :size="14" class="suggestion-icon"><component :is="prompt.icon" /></el-icon>
          </button>
        </div>
      </div>

      <!-- 消息列表 -->
      <ChatMessage
        v-for="msg in chatStore.messages"
        :key="msg.id"
        :message="msg"
        @regenerate="regenerate"
      />

      <!-- 加载动画 -->
      <div v-if="chatStore.isSending && lastMsgLoading" class="typing-indicator">
        <span></span><span></span><span></span>
      </div>
    </div>

    <!-- 输入框 -->
    <ChatInput
      :disabled="chatStore.isSending"
      @send="sendMessage"
      @stop="stopGeneration"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useChat } from '@/composables/useChat'
import ChatMessage from '@/components/chat/ChatMessage.vue'
import ChatInput from '@/components/chat/ChatInput.vue'

const chatStore = useChatStore()
const { sendMessage, regenerate, stopGeneration, loadModels, scrollToBottom } = useChat()
const messagesContainer = ref<HTMLElement>()

const lastMsgLoading = computed(() => {
  const msgs = chatStore.messages
  if (msgs.length === 0) return false
  return msgs[msgs.length - 1].role === 'assistant' && msgs[msgs.length - 1].content === ''
})

const suggestedPrompts = [
  { title: '代码编写', desc: '用 Python 写一个快速排序算法', content: '请用 Python 写一个快速排序算法，并解释其原理', icon: 'Document' },
  { title: '知识问答', desc: '解释一下什么是量子计算', content: '什么是量子计算？它与传统计算有什么不同？', icon: 'QuestionFilled' },
  { title: '文本处理', desc: '帮我写一封商务邮件', content: '请帮我写一封正式的商务邮件，向客户介绍我们的新产品', icon: 'Edit' },
  { title: '数据分析', desc: '分析数据的趋势和规律', content: '请解释一下回归分析和分类分析的区别', icon: 'DataAnalysis' },
]

function doScrollToBottom() {
  nextTick(() => {
    const el = messagesContainer.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

scrollToBottom.value = doScrollToBottom

watch(() => chatStore.messages.length, doScrollToBottom)

function handleNewChat() {
  chatStore.createConversation()
}

onMounted(() => {
  loadModels()
  // 确保有当前对话
  if (!chatStore.currentConversationId && chatStore.conversations.length === 0) {
    chatStore.createConversation()
  }
})
</script>

<style scoped>
.chat-page {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 24px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-card);
  flex-shrink: 0;
  min-height: 48px;
}

.conv-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text);
  max-width: 500px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 欢迎页 */
.welcome-screen {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  text-align: center;
}

.welcome-kicker {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.14em;
  color: var(--color-primary);
  margin-bottom: 10px;
}

.welcome-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--color-text);
  margin-bottom: 8px;
  letter-spacing: -0.3px;
}

.welcome-desc {
  font-size: 13px;
  color: var(--color-text-secondary);
  margin-bottom: 32px;
}

.suggestions {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
  max-width: 620px;
  width: 100%;
}

.suggestion-card {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 13px 14px;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  cursor: pointer;
  text-align: left;
  transition: border-color 0.15s, background 0.15s;
  font-family: inherit;
}

.suggestion-card:hover {
  border-color: var(--color-primary-light);
  background: var(--color-primary-10);
}

.suggestion-prefix {
  font-size: 13px;
  color: var(--color-primary);
  line-height: 1.5;
  flex-shrink: 0;
}

.suggestion-text {
  flex: 1;
  min-width: 0;
}

.suggestion-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 2px;
}

.suggestion-desc {
  font-size: 12px;
  color: var(--color-text-light);
}

.suggestion-icon {
  color: var(--color-text-light);
  flex-shrink: 0;
  margin-top: 2px;
}

/* 消息区域 */
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

/* 加载动画 */
.typing-indicator {
  display: flex;
  gap: 4px;
  padding: 12px 14px;
  align-self: flex-start;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  margin-left: 2px;
}

.typing-indicator span {
  width: 8px;
  height: 8px;
  background: var(--color-text-light);
  border-radius: 2px;
  animation: typing 1.4s infinite ease-in-out both;
}

.typing-indicator span:nth-child(1) { animation-delay: 0s; }
.typing-indicator span:nth-child(2) { animation-delay: 0.2s; }
.typing-indicator span:nth-child(3) { animation-delay: 0.4s; }

@keyframes typing {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

@media (max-width: 640px) {
  .suggestions {
    grid-template-columns: 1fr;
  }
  .chat-messages {
    padding: 12px 16px;
  }
}
</style>
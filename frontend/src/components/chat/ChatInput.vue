<template>
  <div class="chat-input-wrapper">
    <div class="chat-input-inner">
      <div class="input-container">
        <textarea
          ref="textareaRef"
          v-model="inputText"
          class="chat-textarea"
          :placeholder="disabled ? 'AI 正在回复中...' : '输入消息，Enter 发送，Shift+Enter 换行'"
          :disabled="disabled"
          :rows="1"
          @keydown="handleKeydown"
          @input="autoResize"
        />
        <div class="input-actions">
          <div class="input-left">
            <ModelSelector
              :models="chatStore.models"
              :model-value="chatStore.currentModel"
              @update:model-value="chatStore.currentModel = $event"
            />
          </div>
          <div class="input-right">
            <button
              v-if="disabled"
              class="stop-btn"
              @click="$emit('stop')"
              title="停止生成"
            >
              <el-icon :size="16"><VideoPause /></el-icon>
            </button>
            <button
              v-else
              class="send-btn"
              :disabled="!inputText.trim()"
              @click="send"
              title="发送消息"
            >
              <el-icon :size="18"><Position /></el-icon>
            </button>
          </div>
        </div>
      </div>
    </div>
    <p class="input-hint">AI 生成内容仅供参考，请核实重要信息</p>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue'
import { useChatStore } from '@/stores/chat'
import ModelSelector from './ModelSelector.vue'

defineProps<{ disabled: boolean }>()
const emit = defineEmits<{ send: [text: string]; stop: [] }>()

const chatStore = useChatStore()
const inputText = ref('')
const textareaRef = ref<HTMLTextAreaElement>()

function send() {
  const text = inputText.value.trim()
  if (!text) return
  emit('send', text)
  inputText.value = ''
  nextTick(() => {
    if (textareaRef.value) {
      textareaRef.value.style.height = 'auto'
    }
  })
}

function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}
</script>

<style scoped>
.chat-input-wrapper {
  padding: 16px 24px 12px;
  flex-shrink: 0;
}

.chat-input-inner {
  max-width: 800px;
  margin: 0 auto;
}

.input-container {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: 8px 12px 6px;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.input-container:focus-within {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-10);
}

.chat-textarea {
  width: 100%;
  border: none;
  outline: none;
  resize: none;
  font-size: 14px;
  font-family: inherit;
  line-height: 1.6;
  color: var(--color-text);
  background: transparent;
  padding: 4px 0;
  min-height: 24px;
  max-height: 200px;
}

.chat-textarea::placeholder {
  color: var(--color-text-light);
}

.chat-textarea:disabled {
  opacity: 0.6;
}

.input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 4px;
}

.input-left {
  display: flex;
  align-items: center;
}

.input-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.send-btn {
  width: 34px;
  height: 34px;
  border-radius: 4px;
  border: none;
  background: var(--color-primary);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s;
}

.send-btn:hover:not(:disabled) {
  background: var(--color-primary-hover);
}

.send-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.stop-btn {
  width: 34px;
  height: 34px;
  border-radius: 4px;
  border: 1px solid var(--color-border);
  background: var(--color-card);
  color: var(--color-danger);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}

.stop-btn:hover {
  background: #fef2f2;
  border-color: var(--color-danger);
}

.input-hint {
  font-size: 11px;
  color: var(--color-text-light);
  text-align: center;
  margin-top: 8px;
}
</style>
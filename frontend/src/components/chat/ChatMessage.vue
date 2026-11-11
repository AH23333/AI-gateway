<template>
  <div class="chat-message" :class="message.role">
    <div class="message-body">
      <div class="message-meta">
        <span class="message-role mono">{{ message.role === 'user' ? 'user' : 'assistant' }}</span>
        <el-tag
          v-if="message.role === 'assistant' && message.decision"
          size="small"
          type="info"
          effect="plain"
          class="decision-tag mono"
        >
          {{ message.decision }}
        </el-tag>
        <span class="message-time mono">{{ formatTime(message.timestamp) }}</span>
      </div>
      <!-- 思考型模型透传的推理过程（不入库不计费，仅前端展示） -->
      <details v-if="message.reasoning" class="reasoning-box">
        <summary>
          <el-icon :size="12"><View /></el-icon>
          思考过程
        </summary>
        <div class="reasoning-text">{{ message.reasoning }}</div>
      </details>
      <div class="message-content">
        <MarkdownContent v-if="message.role === 'assistant'" :content="message.content" />
        <template v-else>{{ message.content }}</template>
      </div>
      <div v-if="message.role === 'assistant' && message.traceId" class="trace-line" title="链路追踪 ID，可在对话历史中定位本次调用">
        Trace: {{ message.traceId }}
      </div>
      <div v-if="message.role === 'assistant' && message.content" class="message-actions">
        <button class="action-btn" @click="copyContent(message.content)" title="复制">
          <el-icon :size="15"><CopyDocument /></el-icon>
        </button>
        <button class="action-btn" @click="$emit('regenerate')" title="重新生成">
          <el-icon :size="15"><Refresh /></el-icon>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import MarkdownContent from './MarkdownContent.vue'
import type { LocalMessage } from '@/stores/chat'

defineProps<{ message: LocalMessage }>()
defineEmits<{ regenerate: [] }>()

function formatTime(ts: number) {
  return dayjs(ts).format('HH:mm')
}

function copyContent(text: string) {
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success('已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}
</script>

<style scoped>
.chat-message {
  display: flex;
  gap: 12px;
  padding: 6px 0;
  animation: msgIn 0.35s ease;
}

@keyframes msgIn {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}

.chat-message.user {
  flex-direction: row-reverse;
}

.message-body {
  max-width: 78%;
  min-width: 0;
}

.message-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
  padding: 0 4px;
}

.message-role {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--color-text-secondary);
  text-transform: uppercase;
}

.message-time {
  font-size: 11px;
  color: var(--color-text-light);
}

.chat-message.user .message-meta {
  justify-content: flex-end;
}

.decision-tag {
  font-family: var(--font-mono);
}

/* 思考过程折叠块 */
.reasoning-box {
  margin-bottom: 6px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius);
  background: var(--color-bg);
  overflow: hidden;
}

.reasoning-box summary {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 6px 12px;
  font-size: 12px;
  color: var(--color-text-secondary);
  cursor: pointer;
  user-select: none;
  list-style: none;
}

.reasoning-box summary::-webkit-details-marker {
  display: none;
}

.reasoning-box summary:hover {
  color: var(--color-primary);
}

.reasoning-text {
  max-height: 260px;
  overflow-y: auto;
  padding: 4px 12px 10px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--color-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
  border-top: 1px dashed var(--color-border);
}

.trace-line {
  margin-top: 4px;
  padding: 0 4px;
  font-size: 11px;
  color: var(--color-text-light);
  font-family: var(--font-mono);
}

.message-content {
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}

/* 用户：实心主色块 */
.chat-message.user .message-content {
  background: var(--color-primary);
  color: #fff;
  padding: 10px 14px;
  border-radius: var(--radius);
}

/* 助手：终端输出式，无气泡，左侧青绿细导轨 */
.chat-message.assistant .message-content {
  background: transparent;
  color: var(--color-text);
  padding: 2px 0 2px 12px;
  border-left: 2px solid var(--color-primary-light);
}

.message-actions {
  display: flex;
  gap: 4px;
  margin-top: 6px;
  padding: 0 4px;
  opacity: 0;
  transition: opacity 0.2s;
}

.chat-message:hover .message-actions {
  opacity: 1;
}

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--color-border);
  background: var(--color-card);
  border-radius: 4px;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.15s;
}

.action-btn:hover {
  background: var(--color-bg);
  color: var(--color-text);
  border-color: var(--color-border);
}
</style>
<template>
  <div class="page-container">
    <PageHeader title="对话历史" subtitle="浏览调用记录，按内容关键词检索会话" />

    <!-- 搜索筛选栏 -->
    <el-card shadow="never" class="filter-card">
      <el-form inline class="filter-form" @submit.prevent="handleSearch">
        <el-form-item label="关键词">
          <el-input
            v-model="keyword"
            placeholder="搜索会话内容（如：快速排序）..."
            clearable
            style="width: 320px"
            @keyup.enter="handleSearch"
            @clear="handleReset"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">搜索</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
        <el-form-item>
          <el-tag :type="mode === 'search' ? 'warning' : 'info'" effect="plain" size="large">
            {{ mode === 'search' ? `检索模式：命中 ${total} 条消息` : '浏览模式：全部调用记录' }}
          </el-tag>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 浏览模式：调用日志列表 -->
    <el-card v-if="mode === 'browse'" shadow="never" class="table-card">
      <el-table :data="logs" v-loading="loading" stripe row-key="id" @row-click="openDetail($event.traceId)">
        <el-table-column prop="traceId" label="Trace ID" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <code class="mono">{{ row.traceId }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="modelCode" label="模型" min-width="130" />
        <el-table-column prop="totalTokens" label="总 Tokens" width="100" align="right">
          <template #default="{ row }">
            <el-tag size="small" type="primary" effect="plain">{{ row.totalTokens }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="costAmount" label="费用" width="95" align="right">
          <template #default="{ row }">
            {{ row.costAmount ? '¥' + Number(row.costAmount).toFixed(4) : '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="耗时(ms)" width="95" align="right" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.success === 1 ? 'success' : 'danger'" size="small" effect="plain">
              {{ row.success === 1 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="降级" width="70" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.fallbackUsed === 1" type="warning" size="small" effect="plain">降级</el-tag>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" min-width="150">
          <template #default="{ row }">{{ formatUtcTime(row.gmtCreate) }}</template>
        </el-table-column>
      </el-table>
      <div class="pagination-wrapper">
        <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next"
          @current-change="loadData" @size-change="loadData" />
      </div>
    </el-card>

    <!-- 检索模式：命中的会话消息 -->
    <el-card v-else shadow="never" class="table-card">
      <el-table :data="searchResults" v-loading="loading" stripe row-key="id" @row-click="openDetail($event.traceId)">
        <el-table-column prop="traceId" label="Trace ID" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <code class="mono">{{ row.traceId }}</code>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="roleTagType(row.role)" effect="plain" size="small">{{ roleLabel(row.role) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容预览" min-width="340" show-overflow-tooltip />
        <el-table-column label="时间" min-width="150">
          <template #default="{ row }">{{ formatUtcTime(row.gmtCreate) }}</template>
        </el-table-column>
      </el-table>
      <div class="pagination-wrapper">
        <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next"
          @current-change="loadData" @size-change="loadData" />
      </div>
    </el-card>

    <!-- 调用详情抽屉：日志元信息 + 会话回放 + 路由决策 -->
    <el-drawer v-model="detailVisible" title="调用详情" direction="rtl" size="70%">
      <div v-if="detailLoading" class="detail-loading">加载中...</div>
      <div v-else-if="detail" class="detail-content">
        <div class="detail-header">
          <div class="detail-meta">
            <span class="meta-item"><strong>Trace ID:</strong> <code class="mono">{{ detail.log.traceId }}</code></span>
            <span class="meta-item"><strong>模型:</strong> {{ detail.log.modelCode }}</span>
            <span class="meta-item"><strong>时间:</strong> {{ formatUtcTime(detail.log.gmtCreate) }}</span>
            <span class="meta-item"><strong>Tokens:</strong> {{ detail.log.totalTokens }}（输入 {{ detail.log.promptTokens }} / 输出 {{ detail.log.completionTokens }}）</span>
            <span class="meta-item"><strong>费用:</strong> ¥{{ Number(detail.log.costAmount ?? 0).toFixed(4) }}（价格版本 v{{ detail.log.priceVersion }}）</span>
            <span class="meta-item"><strong>耗时:</strong> {{ detail.log.latencyMs }}ms<template v-if="detail.log.ttftMs">（首字 {{ detail.log.ttftMs }}ms）</template></span>
            <span class="meta-item">
              <strong>状态:</strong>
              <el-tag :type="detail.log.success === 1 ? 'success' : 'danger'" size="small" effect="plain">
                {{ detail.log.success === 1 ? '成功' : '失败' }}
              </el-tag>
              <el-tag v-if="detail.log.fallbackUsed === 1" type="warning" size="small" effect="plain" style="margin-left: 4px;">降级</el-tag>
              <el-tag v-if="detail.log.interrupted === 1" type="info" size="small" effect="plain" style="margin-left: 4px;">中断</el-tag>
            </span>
          </div>
          <div v-if="prettyDecision" class="decision-block">
            <div class="decision-title">智能路由决策</div>
            <pre class="decision-json">{{ prettyDecision }}</pre>
          </div>
        </div>
        <div class="messages-container">
          <div
            v-for="(msg, index) in detail.messages"
            :key="index"
            :class="['message-item', msg.role]"
          >
            <div class="message-role">
              <el-tag :type="roleTagType(msg.role)" effect="plain">{{ roleLabel(msg.role) }}</el-tag>
            </div>
            <div class="message-content">
              <MarkdownContent :content="msg.content" />
            </div>
          </div>
          <el-empty v-if="detail.messages.length === 0" description="该调用未落存会话内容（可能为流式中断或非对话调用）" :image-size="70" />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import MarkdownContent from '@/components/chat/MarkdownContent.vue'
import { getUserChatLogs, searchChatMessages, getChatLogDetail } from '@/api/admin'
import { formatUtcTime } from '@/utils/format'
import type { InvokeLog, ChatMessageRecord, ChatLogDetail } from '@/api/types'

const mode = ref<'browse' | 'search'>('browse')
const keyword = ref('')
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)

// 浏览模式：调用日志；检索模式：命中的消息
const logs = ref<InvokeLog[]>([])
const searchResults = ref<ChatMessageRecord[]>([])

// 详情抽屉
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<ChatLogDetail | null>(null)

const prettyDecision = computed(() => {
  const raw = detail.value?.log?.routingDecision
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
})

async function loadData() {
  loading.value = true
  try {
    if (mode.value === 'search') {
      if (!keyword.value.trim()) {
        handleReset()
        return
      }
      const resp = await searchChatMessages({
        keyword: keyword.value.trim(),
        page: page.value,
        size: size.value,
      })
      searchResults.value = resp.data?.records ?? []
      total.value = resp.data?.total ?? 0
    } else {
      const resp = await getUserChatLogs({ page: page.value, size: size.value })
      logs.value = resp.data?.records ?? []
      total.value = resp.data?.total ?? 0
    }
  } catch { /* ignore */ } finally {
    loading.value = false
  }
}

function handleSearch() {
  if (!keyword.value.trim()) {
    ElMessage.warning('请输入检索关键词')
    return
  }
  mode.value = 'search'
  page.value = 1
  loadData()
}

function handleReset() {
  mode.value = 'browse'
  keyword.value = ''
  page.value = 1
  loadData()
}

async function openDetail(traceId: string) {
  if (!traceId) return
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const resp = await getChatLogDetail(traceId)
    detail.value = resp.data
  } catch {
    ElMessage.error('加载详情失败')
  } finally {
    detailLoading.value = false
  }
}

function roleTagType(role: string): 'primary' | 'success' | 'warning' | 'info' {
  switch (role) {
    case 'user': return 'primary'
    case 'assistant': return 'success'
    case 'system': return 'info'
    default: return 'info'
  }
}

function roleLabel(role: string): string {
  switch (role) {
    case 'user': return '用户'
    case 'assistant': return '助手'
    case 'system': return '系统'
    default: return role
  }
}

onMounted(loadData)
</script>

<style scoped>
.page-container {
  padding: 20px;
}

.filter-card {
  margin-bottom: 20px;
}

.filter-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.mono {
  font-family: var(--font-mono);
  font-size: 12px;
}

.muted {
  color: var(--color-text-light);
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
}

.detail-header {
  flex-shrink: 0;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--el-border-color-light);
  margin-bottom: 16px;
}

.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 13px;
}

.meta-item {
  color: var(--el-text-color-secondary);
  display: flex;
  align-items: center;
  gap: 4px;
}

/* 路由决策回放 */
.decision-block {
  margin-top: 12px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 12px;
}

.decision-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}

.decision-json {
  margin: 0;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color);
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

/* 让详情内容撑满整个抽屉，消息区域占满剩余高度 */
.page-container :deep(.el-drawer__body) {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.detail-content {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.messages-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-right: 8px;
}

.message-item {
  display: flex;
  gap: 12px;
  animation: fadeIn 0.3s ease;
}

.message-item.user {
  flex-direction: row-reverse;
}

.message-item.user .message-content {
  text-align: right;
}

.message-role {
  flex-shrink: 0;
  margin-top: 4px;
}

.message-content {
  flex: 1;
  min-width: 0;
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 12px 16px;
}

.message-item.user .message-content {
  background: var(--el-color-primary-light-9);
}

.message-content :deep(.markdown-body) {
  font-size: 14px;
  line-height: 1.7;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.detail-loading {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 200px;
  color: var(--el-text-color-placeholder);
}
</style>

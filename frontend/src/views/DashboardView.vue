<template>
  <div class="page-container">
    <PageHeader :title="isAdmin ? '仪表盘' : '我的仪表盘'" :subtitle="isAdmin ? 'AI Gateway 全站运行概览' : '个人配额与用量概览'" />

    <!-- 顶部统计卡片 -->
    <el-row :gutter="20" class="stats-row">
      <el-col :xs="12" :sm="6" v-for="card in statCards" :key="card.label">
        <StatCard
          :label="card.label"
          :value="card.value"
          :icon="card.icon"
          :color="card.color"
          :show-progress="card.showProgress"
          :pct="card.pct"
          :sub-text="card.subText"
        />
      </el-col>
    </el-row>

    <!-- 管理员：今日运行指标 / 普通用户：配额水位 -->
    <el-row :gutter="20" class="stats-row">
      <el-col :xs="12" :sm="6" v-for="card in secondRowCards" :key="card.label">
        <StatCard
          :label="card.label"
          :value="card.value"
          :icon="card.icon"
          :color="card.color"
          :show-progress="card.showProgress"
          :pct="card.pct"
          :sub-text="card.subText"
        />
      </el-col>
    </el-row>

    <!-- 近 7 日趋势（仅管理员有全站趋势数据） -->
    <el-row v-if="isAdmin" :gutter="20" class="stats-row">
      <el-col :span="24">
        <el-card shadow="never" class="table-card">
          <template #header>
            <span class="card-title">全站近 7 日调用趋势</span>
          </template>
          <el-table :data="trend" stripe size="small">
            <el-table-column prop="statDate" label="日期" min-width="120" />
            <el-table-column prop="calls" label="调用次数" align="right" />
            <el-table-column prop="tokens" label="Tokens" align="right" sortable />
            <el-table-column prop="cost" label="费用" align="right">
              <template #default="{ row }">
                {{ formatCost(row.cost) }}
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 管理员：模型分布 / 普通用户：个人模型用量 -->
    <el-row :gutter="20" class="stats-row">
      <el-col :span="24">
        <el-card shadow="never" class="table-card">
          <template #header>
            <span class="card-title">{{ isAdmin ? '近 7 日模型用量分布' : '我的模型用量分布（近期调用聚合）' }}</span>
          </template>
          <el-table :data="modelDist" stripe size="small">
            <el-table-column prop="modelCode" label="模型" min-width="140" />
            <el-table-column prop="calls" label="调用次数" align="right" />
            <el-table-column prop="tokens" label="Tokens" align="right" />
            <el-table-column prop="cost" label="费用" align="right">
              <template #default="{ row }">{{ formatCost(row.cost) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="modelDist.length === 0" description="暂无数据" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 最近调用记录 + 系统信息 -->
    <el-row :gutter="20">
      <el-col :span="16">
        <el-card shadow="never" class="table-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">最近调用记录</span>
              <el-button text type="primary" size="small" @click="refresh">
                <el-icon :size="14"><Refresh /></el-icon>
                刷新
              </el-button>
            </div>
          </template>
          <el-table :data="recentCalls" v-loading="loading" stripe>
            <el-table-column v-if="isAdmin" prop="userId" label="用户ID" width="90" />
            <el-table-column prop="modelCode" label="模型" min-width="130" />
            <el-table-column prop="promptTokens" label="输入 Tokens" width="105" align="right" />
            <el-table-column prop="completionTokens" label="输出 Tokens" width="105" align="right" />
            <el-table-column prop="totalTokens" label="总计" width="90" align="right">
              <template #default="{ row }">
                <el-tag size="small" type="primary" effect="plain">{{ row.totalTokens }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="costAmount" label="费用" width="95" align="right">
              <template #default="{ row }">{{ formatCost(row.costAmount) }}</template>
            </el-table-column>
            <el-table-column label="时间" min-width="150">
              <template #default="{ row }">{{ formatUtcTime(row.gmtCreate) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && recentCalls.length === 0" description="暂无调用记录" :image-size="80" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="table-card">
          <template #header>
            <span class="card-title">系统信息</span>
          </template>
          <div class="sys-info">
            <div class="sys-item">
              <span class="sys-label">网关版本</span>
              <el-tag size="small" class="mono">v2.0.0 模块化单体</el-tag>
            </div>
            <div class="sys-item">
              <span class="sys-label">运行时</span>
              <el-tag size="small" class="mono">Java 21 + Spring Boot 3.2</el-tag>
            </div>
            <div class="sys-item">
              <span class="sys-label">存储</span>
              <el-tag size="small" type="success" class="mono">MySQL 8 + Redis</el-tag>
            </div>
            <div class="sys-item">
              <span class="sys-label">认证方式</span>
              <el-tag size="small" type="danger" class="mono">JWT + API Key</el-tag>
            </div>
            <div class="sys-item">
              <span class="sys-label">智能路由</span>
              <el-tag v-if="isAdmin" size="small" :type="routingEnabled ? 'success' : 'info'" class="mono">
                {{ routingEnabled ? '已开启' : '已关闭' }}
              </el-tag>
              <el-tag v-else size="small" type="success" class="mono">熔断降级 + 打分选型</el-tag>
            </div>
            <div class="sys-item">
              <span class="sys-label">计费模式</span>
              <el-tag size="small" type="warning" class="mono">两段式（预扣 + 回补）</el-tag>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatCard from '@/components/common/StatCard.vue'
import { getUserUsage, getUsage, getUserInfo, getDashboardStats } from '@/api/admin'
import type { TrendPoint, ModelRankItem, AdminDashboard, UserInfo, InvokeLog } from '@/api/types'
import { formatUtcTime } from '@/utils/format'
import { useUserStore } from '@/stores/user'

const store = useUserStore()
const isAdmin = store.isAdmin

const loading = ref(false)
const recentCalls = ref<InvokeLog[]>([])
interface StatCardItem {
  label: string
  value: string
  icon: string
  color: string
  showProgress?: boolean
  pct?: number
  subText?: string
}

const statCards = ref<StatCardItem[]>([])
const secondRowCards = ref<StatCardItem[]>([])
const trend = ref<TrendPoint[]>([])
const modelDist = ref<ModelRankItem[]>([])
const routingEnabled = ref(true)

function formatNumber(n: number | string | null | undefined): string {
  const v = Number(n ?? 0)
  if (v >= 100000) return (v / 1000).toFixed(1) + 'K'
  return v.toLocaleString()
}

function formatCost(c: number | string | null | undefined): string {
  const v = Number(c ?? 0)
  if (v === 0) return '¥0'
  return '¥' + v.toFixed(4)
}

/** 管理员：全站仪表盘 */
async function refreshAdmin() {
  const resp = await getDashboardStats().catch(() => null)
  const s: AdminDashboard | undefined = resp?.data
  if (s) {
    routingEnabled.value = s.routingEnabled === 1
    statCards.value = [
      { label: '总用户数', value: formatNumber(s.totalUsers), icon: 'UserFilled', color: '#2563eb' },
      { label: '活跃模型', value: formatNumber(s.activeModels), icon: 'Cpu', color: '#0d9488' },
      { label: '今日调用', value: formatNumber(s.today?.calls), icon: 'TrendCharts', color: '#16a34a' },
      { label: '今日费用', value: formatCost(s.today?.cost), icon: 'Coin', color: '#d97706' },
    ]
    secondRowCards.value = [
      { label: '今日 Tokens', value: formatNumber(s.today?.tokens), icon: 'DataLine', color: '#0891b2' },
      { label: '今日成功率', value: (s.today?.successRate ?? 0) + '%', icon: 'CircleCheck', color: '#16a34a' },
      {
        label: '今日失败',
        value: formatNumber(s.today?.failedCalls),
        icon: 'Warning',
        color: (s.today?.failedCalls ?? 0) > 0 ? '#dc2626' : '#94a3b8',
      },
      {
        label: '智能路由',
        value: s.routingEnabled === 1 ? '已开启' : '已关闭',
        icon: 'Guide',
        color: s.routingEnabled === 1 ? '#16a34a' : '#94a3b8',
      },
    ]
    trend.value = s.trend7d ?? []
    modelDist.value = s.modelDistribution7d ?? []
  }
}

/** 普通用户：个人仪表盘（配额 + 近期调用聚合） */
async function refreshUser() {
  const resp = await getUserInfo().catch(() => null)
  const s: UserInfo | undefined = resp?.data
  if (s) {
    statCards.value = [
      { label: '今日调用', value: formatNumber(s.todayUsage?.calls), icon: 'TrendCharts', color: '#16a34a' },
      { label: '今日费用', value: formatCost(s.todayUsage?.cost), icon: 'Coin', color: '#d97706' },
      { label: '本月调用', value: formatNumber(s.monthUsage?.calls), icon: 'DataLine', color: '#0891b2' },
      { label: '本月费用', value: formatCost(s.monthUsage?.cost), icon: 'Wallet', color: '#2563eb' },
    ]
    const dailyPct = s.dailyQuota > 0 ? Math.min(100, Math.round((s.usedDaily / s.dailyQuota) * 100)) : 0
    const monthlyPct = s.monthlyQuota > 0 ? Math.min(100, Math.round((s.usedMonthly / s.monthlyQuota) * 100)) : 0
    secondRowCards.value = [
      {
        label: '日配额',
        value: formatNumber(s.usedDaily) + ' / ' + formatNumber(s.dailyQuota),
        icon: 'Timer', color: dailyPct >= 90 ? '#dc2626' : '#d97706',
        showProgress: true, pct: dailyPct, subText: '已用',
      },
      {
        label: '月配额',
        value: formatNumber(s.usedMonthly) + ' / ' + formatNumber(s.monthlyQuota),
        icon: 'Calendar', color: monthlyPct >= 90 ? '#dc2626' : '#0891b2',
        showProgress: true, pct: monthlyPct, subText: '已用',
      },
    ]
  }
}

/** 最近调用 + （普通用户）客户端聚合模型用量分布 */
async function refreshRecent() {
  const fetcher = isAdmin
    ? () => getUsage({ page: 1, size: 10 })
    : () => getUserUsage({ page: 1, size: 10 })
  const resp = await fetcher().catch(() => null)
  if (resp?.data) {
    recentCalls.value = resp.data.records ?? []
  }
  if (!isAdmin) {
    const agg = await getUserUsage({ page: 1, size: 100 }).catch(() => null)
    const map = new Map<string, ModelRankItem>()
    for (const log of agg?.data?.records ?? []) {
      const cur = map.get(log.modelCode) ?? { modelCode: log.modelCode, calls: 0, tokens: 0, cost: 0 }
      cur.calls += 1
      cur.tokens += Number(log.totalTokens ?? 0)
      cur.cost = Number(cur.cost) + Number(log.costAmount ?? 0)
      map.set(log.modelCode, cur)
    }
    modelDist.value = [...map.values()].sort((a, b) => b.calls - a.calls)
  }
}

async function refresh() {
  loading.value = true
  try {
    if (isAdmin) {
      await Promise.all([refreshAdmin(), refreshRecent()])
    } else {
      await Promise.all([refreshUser(), refreshRecent()])
    }
  } catch {
    // ignore
  } finally {
    loading.value = false
  }
}

onMounted(refresh)
</script>

<style scoped>
.stats-row {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-weight: 600;
  font-size: 15px;
}

.sys-info {
  display: flex;
  flex-direction: column;
}

.sys-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px dashed var(--color-border);
}

.sys-item:last-child {
  border-bottom: none;
}

.sys-label {
  font-size: 12.5px;
  color: var(--color-text-secondary);
}
</style>
